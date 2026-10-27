import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PracticeSession } from "../electron/practice-session";
import { PracticeOwner } from "../electron/practice-owner";
import { PracticeJournal } from "../electron/practice-journal";
import { runtimeLocation } from "../electron/java-session";
import { PracticeInputSchema } from "../src/practice-contract";

test("practice schema rejects extra fields and invalid versions", () => {
  const input = {
    questId: "quest",
    questVersion: 1,
    challengeId: "challenge",
    challengeVersion: 1,
  };
  assert.equal(
    PracticeInputSchema.safeParse({ ...input, path: "/private" }).success,
    false,
  );
  assert.equal(
    PracticeInputSchema.safeParse({ ...input, challengeVersion: 0 }).success,
    false,
  );
});
test("real Java opening survives lost response and restart with the same journal identity", async () => {
  const dir = mkdtempSync(join(tmpdir(), "practice-owner-"));
  const journal = new PracticeJournal(join(dir, "journal"));
  const create = () =>
    new PracticeSession(runtimeLocation(resolve("java")), join(dir, "profile"));
  let owner = new PracticeOwner(create, journal);
  try {
    assert.deepEqual(await owner.connect(() => false), {
      ok: false,
      code: "UNAVAILABLE",
    });
    const state = await owner.connect(() => true);
    assert.ok(state.ok);
    assert.ok(state.candidates.length);
    assert.equal(JSON.stringify(state).includes("instructions"), false);
    const c = state.candidates[0];
    const selected = {
      questId: c.questId,
      questVersion: c.questVersion,
      challengeId: c.challengeId,
      challengeVersion: c.challengeVersion,
    };
    const result = await owner.open(
      selected,
      state.profileId,
      state.sessionId,
      () => true,
    );
    assert.ok(result.ok && result.method === "practice.open");
    assert.ok(result.instructions.length);
    const pending = journal.read()!;
    assert.equal(pending.commandId, result.commandId);
    assert.equal(
      readFileSync(
        join(dir, "journal", "pending-practice.json"),
        "utf8",
      ).includes("instructions"),
      false,
    );
    assert.deepEqual(
      await owner.open(selected, state.profileId, state.sessionId, () => true),
      { ok: false, code: "RECOVERY_REQUIRED" },
    );
    await owner.disconnect();
    owner = new PracticeOwner(create, journal);
    const restarted = await owner.connect(() => true);
    assert.ok(restarted.ok);
    assert.deepEqual(restarted.pending, pending);
    assert.notEqual(restarted.sessionId, state.sessionId);
    assert.deepEqual(
      await owner.recover(state.profileId, state.sessionId, () => true),
      { ok: false, code: "SESSION_MISMATCH" },
    );
    const replay = await owner.recover(
      restarted.profileId,
      restarted.sessionId,
      () => true,
    );
    assert.ok(replay.ok && replay.method === "practice.open");
    assert.deepEqual(replay.receipt, result.receipt);
    assert.equal(replay.instructions, result.instructions);
    await owner.acknowledge(
      result.commandId,
      restarted.profileId,
      restarted.sessionId,
      () => true,
    );
    assert.equal(journal.read(), null);
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
test("two independent Java processes cannot own the same disposable profile", async () => {
  const dir = mkdtempSync(join(tmpdir(), "practice-owner-conflict-"));
  const first = new PracticeSession(
    runtimeLocation(resolve("java")),
    join(dir, "profile"),
  );
  const second = new PracticeSession(
    runtimeLocation(resolve("java")),
    join(dir, "profile"),
  );
  try {
    await first.identity();
    await assert.rejects(second.identity());
  } finally {
    await first.stop();
    await second.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("practice transport rejects malformed/private/oversized responses and terminates timed out processes", async () => {
  const { writeFileSync } = await import("node:fs");
  const { randomUUID } = await import("node:crypto");
  for (const mode of [
    "private",
    "oversized",
    "wrong-session",
    "timeout",
    "crash",
  ]) {
    const dir = mkdtempSync(join(tmpdir(), "practice-pipe-"));
    const executable = join(dir, "pipe");
    const profileId = randomUUID(),
      sessionId = randomUUID();
    const reply =
      mode === "oversized"
        ? "process.stdout.write('x'.repeat(524289));"
        : mode === "timeout"
          ? ""
          : mode === "crash"
            ? "process.exit(1);"
            : `process.stdout.write(JSON.stringify({v:1,id:r.id,ok:true,method:'practice.list',profileId:'${profileId}',sessionId:'${mode === "wrong-session" ? randomUUID() : sessionId}',candidates:[]${mode === "private" ? ",instructions:'PRIVATE'" : ""}})+'\\n');`;
    writeFileSync(
      executable,
      `#!${process.execPath}\nprocess.stdout.write(JSON.stringify({v:1,type:'ready',profileId:'${profileId}',sessionId:'${sessionId}'})+'\\n');process.stdin.on('data',b=>{const r=JSON.parse(b);${reply}});process.stdin.on('end',()=>process.exit(0));`,
      { mode: 0o700 },
    );
    const s = new PracticeSession(
      { java: executable, classpath: "unused", cwd: dir },
      join(dir, "profile"),
      500,
    );
    try {
      const identity = await s.identity();
      const result = await s.request({ method: "practice.list", ...identity });
      assert.equal(result.ok, false);
      assert.equal(JSON.stringify(result).includes("PRIVATE"), false);
      await s.stop();
      assert.throws(() => process.kill(s.pid ?? -999999, 0));
    } finally {
      await s.stop();
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

test("hard crash before authorization commit leaves no exposure and the same command can recover", async () => {
  const { spawn } = await import("node:child_process");
  const { default: EventEmitter } = await import("node:events");
  const once = EventEmitter.once;
  const { randomUUID } = await import("node:crypto");
  const dir = mkdtempSync(join(tmpdir(), "practice-crash-before-"));
  let session = new PracticeSession(
    runtimeLocation(resolve("java")),
    join(dir, "profile"),
  );
  let blocker: ReturnType<typeof spawn> | undefined;
  try {
    const identity = await session.identity();
    const listing = await session.request({
      method: "practice.list",
      ...identity,
    });
    assert.ok(listing.ok && listing.method === "practice.list");
    const c = listing.candidates[0],
      commandId = randomUUID();
    // Isolated fixture holds SQLite's writer slot. No production crash hooks or real DBs.
    blocker = spawn(
      "python3",
      [
        "-c",
        "import sqlite3,sys; c=sqlite3.connect(sys.argv[1]); c.execute('BEGIN IMMEDIATE'); print('locked',flush=True); sys.stdin.read(); c.rollback()",
        join(dir, "profile", "codefit.db"),
      ],
      { stdio: ["pipe", "pipe", "pipe"] },
    );
    await once(blocker.stdout!, "data");
    const request = {
      method: "practice.open" as const,
      ...identity,
      commandId,
      questId: c.questId,
      questVersion: c.questVersion,
      challengeId: c.challengeId,
      challengeVersion: c.challengeVersion,
    };
    const interrupted = session.request(request);
    process.kill(session.pid!, "SIGKILL");
    assert.equal((await interrupted).ok, false);
    const released = once(blocker, "close");
    blocker.stdin!.end();
    await released;
    blocker = undefined;
    await session.stop();
    session = new PracticeSession(
      runtimeLocation(resolve("java")),
      join(dir, "profile"),
    );
    const next = await session.identity();
    const status = await session.request({
      method: "practice.status",
      ...next,
      commandId,
    });
    assert.ok(status.ok && status.method === "practice.status");
    assert.equal(status.receipt, null);
    const recovered = await session.request({ ...request, ...next });
    assert.ok(recovered.ok && recovered.method === "practice.open");
    const replay = await session.request({ ...request, ...next });
    assert.ok(replay.ok && replay.method === "practice.open");
    assert.deepEqual(replay.receipt, recovered.receipt);
  } finally {
    if (blocker) {
      blocker.stdin!.end();
      blocker.kill();
    }
    await session.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});
