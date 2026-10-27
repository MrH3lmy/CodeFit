import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { PracticeSession } from "../electron/practice-session";
import { PracticeOwner, draftDigest } from "../electron/practice-owner";
import { PracticeJournal } from "../electron/practice-journal";
import { runtimeLocation } from "../electron/java-session";
import { DraftInputSchema } from "../src/practice-contract";
async function open(s: PracticeSession) {
  const i = await s.identity(),
    l = await s.request({ method: "practice.list", ...i });
  assert.ok(l.ok && l.method === "practice.list");
  const { questId, questVersion, challengeId, challengeVersion } =
    l.candidates[0];
  const o = await s.request({
    method: "practice.open",
    ...i,
    commandId: randomUUID(),
    questId,
    questVersion,
    challengeId,
    challengeVersion,
  });
  assert.ok(o.ok && o.method === "practice.open");
  const r = await s.request({
    method: "practice.presentation.resume",
    ...i,
    presentationId: o.receipt.presentationId,
  });
  assert.ok(r.ok && r.method === "practice.presentation.resume");
  return { i, d: r.draft };
}
test("draft schema bounds inputs and rejects malformed Unicode and additional fields", () => {
  const x = {
    presentationId: `desktop-practice-${randomUUID()}`,
    expectedRevision: 0,
    source: "class Synthetic {}",
    reflection: "",
  };
  assert.ok(DraftInputSchema.safeParse(x).success);
  for (const bad of [
    { ...x, source: "x".repeat(32769) },
    { ...x, source: "\ud800" },
    { ...x, expectedRevision: -1 },
    { ...x, path: "private" },
    { ...x, reflection: "x".repeat(8001) },
  ])
    assert.equal(DraftInputSchema.safeParse(bad).success, false);
  assert.notEqual(
    draftDigest(x),
    draftDigest({ ...x, source: x.source + " " }),
  );
});
test("real Java CAS, same-command replay, restart and metadata privacy", async () => {
  const dir = mkdtempSync(join(tmpdir(), "draft-cas-"));
  let s = new PracticeSession(runtimeLocation(resolve("java")), join(dir, "p"));
  try {
    const { i, d } = await open(s);
    const input = {
      presentationId: d.presentationId,
      expectedRevision: d.revision,
      source: "class Synthetic {}",
      reflection: "synthetic notes",
    };
    const req = {
      method: "practice.draft.save" as const,
      ...i,
      commandId: randomUUID(),
      ...input,
    };
    const a = await s.request(req);
    assert.ok(
      a.ok && a.method === "practice.draft.save" && a.saved.outcome === "SAVED",
    );
    assert.equal(a.saved.digest, draftDigest(input));
    const b = await s.request(req);
    assert.ok(b.ok && b.method === "practice.draft.save");
    assert.deepEqual(a.saved, b.saved);
    const conflict = await s.request({ ...req, source: "changed" });
    assert.ok(!conflict.ok && conflict.code === "COMMAND_ID_CONFLICT");
    const stale = await s.request({
      ...req,
      commandId: randomUUID(),
      source: "stale",
    });
    assert.ok(
      stale.ok &&
        stale.method === "practice.draft.save" &&
        stale.saved.outcome === "CONFLICT",
    );
    await s.stop();
    s = new PracticeSession(runtimeLocation(resolve("java")), join(dir, "p"));
    const fresh = await s.identity();
    const r = await s.request({
      method: "practice.draft.read",
      ...fresh,
      presentationId: d.presentationId,
    });
    assert.ok(r.ok && r.method === "practice.draft.read");
    assert.equal(r.draft.source, input.source);
    assert.equal(r.draft.reflection, input.reflection);
    const recent = await s.request({
      method: "practice.presentations",
      ...fresh,
    });
    assert.ok(recent.ok && recent.method === "practice.presentations");
    assert.equal(recent.presentations.length, 1);
    assert.equal(JSON.stringify(recent).includes("Synthetic"), false);
    assert.equal(
      (
        await s.request({
          method: "practice.draft.read",
          ...fresh,
          presentationId: `desktop-practice-${randomUUID()}`,
        })
      ).ok,
      false,
    );
  } finally {
    await s.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});
test("lost committed save reconciles original metadata-only journal after restart", async () => {
  const dir = mkdtempSync(join(tmpdir(), "draft-lost-")),
    journal = new PracticeJournal(join(dir, "j"));
  let transport: PracticeSession;
  const create = () =>
    (transport = new PracticeSession(
      runtimeLocation(resolve("java")),
      join(dir, "p"),
    ));
  let owner = new PracticeOwner(create, journal);
  try {
    const state = await owner.connect(() => true);
    assert.ok(state.ok);
    const { questId, questVersion, challengeId, challengeVersion } =
      state.candidates[0];
    const o = await owner.open(
      { questId, questVersion, challengeId, challengeVersion },
      state.profileId,
      state.sessionId,
      () => true,
    );
    assert.ok(o.ok && o.method === "practice.open");
    await owner.acknowledge(
      o.commandId,
      state.profileId,
      state.sessionId,
      () => true,
    );
    const r = await owner.resume(
      o.receipt.presentationId,
      state.profileId,
      state.sessionId,
      () => true,
    );
    assert.ok(r.ok && r.method === "practice.presentation.resume");
    const original = transport!.request.bind(transport!);
    transport!.request = async (c) => {
      const result = await original(c);
      return c.method === "practice.draft.save"
        ? { ok: false, code: "UNAVAILABLE" }
        : result;
    };
    const input = {
      presentationId: r.draft.presentationId,
      expectedRevision: r.draft.revision,
      source: "class PrivateSynthetic {}",
      reflection: "PRIVATE_NOTES",
    };
    assert.equal(
      (
        await owner.saveDraft(
          input,
          state.profileId,
          state.sessionId,
          () => true,
        )
      ).ok,
      false,
    );
    const bytes = readFileSync(
      join(dir, "j", "drafts", "pending-draft.json"),
      "utf8",
    );
    assert.equal(bytes.includes("PrivateSynthetic"), false);
    assert.equal(bytes.includes("PRIVATE_NOTES"), false);
    assert.deepEqual(
      await owner.saveDraft(
        { ...input, source: "newer" },
        state.profileId,
        state.sessionId,
        () => true,
      ),
      { ok: false, code: "RECOVERY_REQUIRED" },
    );
    await owner.disconnect();
    owner = new PracticeOwner(create, journal);
    const fresh = await owner.connect(() => true);
    assert.ok(fresh.ok && fresh.pendingSave);
    const result = await owner.reconcileDraft(
      input.presentationId,
      fresh.profileId,
      fresh.sessionId,
      () => true,
    );
    assert.ok(
      result.ok &&
        result.method === "practice.draft.status" &&
        result.saved?.outcome === "SAVED",
    );
    assert.equal(result.commandId, JSON.parse(bytes).commandId);
    const read = await owner.readDraft(
      input.presentationId,
      fresh.profileId,
      fresh.sessionId,
      () => true,
    );
    assert.ok(read.ok && read.method === "practice.draft.read");
    assert.equal(read.draft.source, input.source);
    assert.deepEqual(
      await owner.readDraft(
        input.presentationId,
        state.profileId,
        state.sessionId,
        () => true,
      ),
      { ok: false, code: "SESSION_MISMATCH" },
    );
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
test("hard crash before save commit preserves acknowledged bytes and original command can retry", async () => {
  const { spawn } = await import("node:child_process"),
    { default: Emitter } = await import("node:events");
  const once = Emitter.once;
  const dir = mkdtempSync(join(tmpdir(), "draft-kill-"));
  let s = new PracticeSession(runtimeLocation(resolve("java")), join(dir, "p")),
    blocker: ReturnType<typeof spawn> | undefined;
  try {
    const { i, d } = await open(s);
    blocker = spawn(
      "python3",
      [
        "-c",
        "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('BEGIN IMMEDIATE'); print('locked',flush=True); sys.stdin.read(); c.rollback()",
        join(dir, "p", "codefit.db"),
      ],
      { stdio: ["pipe", "pipe", "pipe"] },
    );
    await once(blocker.stdout!, "data");
    const req = {
      method: "practice.draft.save" as const,
      ...i,
      commandId: randomUUID(),
      presentationId: d.presentationId,
      expectedRevision: d.revision,
      source: "class Interrupted {}",
      reflection: "",
    };
    const waiting = s.request(req);
    process.kill(s.pid!, "SIGKILL");
    assert.equal((await waiting).ok, false);
    const released = once(blocker, "close");
    blocker.stdin!.end();
    await released;
    blocker = undefined;
    await s.stop();
    s = new PracticeSession(runtimeLocation(resolve("java")), join(dir, "p"));
    const fresh = await s.identity();
    const status = await s.request({
      method: "practice.draft.status",
      ...fresh,
      commandId: req.commandId,
      presentationId: d.presentationId,
    });
    assert.ok(status.ok && status.method === "practice.draft.status");
    assert.equal(status.saved, null);
    const read = await s.request({
      method: "practice.draft.read",
      ...fresh,
      presentationId: d.presentationId,
    });
    assert.ok(read.ok && read.method === "practice.draft.read");
    assert.equal(read.draft.source === d.source, true);
    const retry = await s.request({ ...req, ...fresh });
    assert.ok(
      retry.ok &&
        retry.method === "practice.draft.save" &&
        retry.saved.outcome === "SAVED",
    );
  } finally {
    blocker?.kill();
    await s.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});
test("lexical highlighting escapes source and handles unterminated comments without execution", async () => {
  const { highlightJava } = await import("../src/java-editor");
  const { renderToStaticMarkup } = await import("react-dom/server");
  const { createElement } = await import("react");
  const markup = renderToStaticMarkup(
    createElement(
      "pre",
      null,
      highlightJava("<script>private()</script> /* " + "/* ".repeat(8000)),
    ),
  );
  assert.equal(markup.includes("<script>"), false);
  assert.ok(markup.includes("&lt;script&gt;"));
  assert.equal((markup.match(/java-comment/g) || []).length, 1);
});
