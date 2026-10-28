import { debriefIdentity } from "../src/practice-debrief-identity";
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { PracticeSession } from "../electron/practice-session";
import { PracticeOwner } from "../electron/practice-owner";
import { PracticeJournal } from "../electron/practice-journal";
import { runtimeLocation } from "../electron/java-session";
import {
  SubmissionInputSchema,
  SubmissionResultSchema,
  type PracticeCommand,
  type PracticeResult,
} from "../src/practice-contract";
const runtime = () => runtimeLocation(resolve("java"));
async function opened(owner: PracticeOwner) {
  const state = await owner.connect(() => true);
  assert.ok(state.ok);
  const { questId, questVersion, challengeId, challengeVersion } =
    state.candidates.find((c) => c.challengeId === "regression-localize")!;
  const o = await owner.open(
    { questId, questVersion, challengeId, challengeVersion },
    state.profileId,
    state.sessionId,
    () => true,
  );
  assert.ok(o.ok && o.method === "practice.open");
  const d = await owner.readDraft(
    o.receipt.presentationId,
    state.profileId,
    state.sessionId,
    () => true,
  );
  assert.ok(d.ok && d.method === "practice.draft.read");
  return { state, d: d.draft };
}
test("submission schemas reject source, extra fields and unsafe revisions", () => {
  const x = {
    presentationId: `desktop-practice-${randomUUID()}`,
    expectedRevision: 1,
    assistance: "UNKNOWN",
  };
  assert.ok(SubmissionInputSchema.safeParse(x).success);
  for (const bad of [
    { ...x, source: "secret" },
    { ...x, assistance: "NONE" },
    { ...x, expectedRevision: 1.5 },
    { ...x, expectedRevision: 9007199254740000 },
    { ...x, presentationId: "draft.v1" },
  ])
    assert.equal(SubmissionInputSchema.safeParse(bad).success, false);
  assert.equal(
    SubmissionResultSchema.safeParse({ stage: "PREPARED", outcome: "PASSED" })
      .success,
    false,
  );
});
test("evaluation DTOs reject private extras, inconsistent stages and unbounded feedback", () => {
  const r = {
    commandId: randomUUID(),
    presentationId: `desktop-practice-${randomUUID()}`,
    draftRevision: 1,
    evaluationRevision: 1,
    assistance: "UNKNOWN",
    challengeId: "regression-localize",
    challengeVersion: 1,
    submittedAt: "2026-10-27T00:00:00Z",
    artifactSha256: "a".repeat(64),
    active: false,
    cancellationRequested: false,
    withdrawn: false,
    stage: "FINALIZED",
    outcome: "PASSED",
    checks: [
      { id: "functional", status: "PASSED", feedback: "Bounded check passed" },
    ],
  };
  assert.ok(SubmissionResultSchema.safeParse(r).success);
  for (const bad of [
    { ...r, source: "private" },
    { ...r, checks: Array(65).fill(r.checks[0]) },
    { ...r, stage: "PREPARED" },
    { ...r, outcome: null },
    { ...r, evaluationRevision: 0 },
    { ...r, evaluationRevision: 1.5 },
    { ...r, evaluationRevision: undefined },
    { ...r, checks: [{ ...r.checks[0], feedback: "x".repeat(2049) }] },
    { ...r, checks: [{ ...r.checks[0], expectedOutput: "secret" }] },
  ])
    assert.equal(SubmissionResultSchema.safeParse(bad).success, false);
});

test("real Java frozen preparation, replay, later edits and cancellation", async () => {
  const dir = mkdtempSync(join(tmpdir(), "eval-real-"));
  const s = new PracticeSession(runtime(), join(dir, "p"));
  const owner = new PracticeOwner(
    () => s,
    new PracticeJournal(join(dir, "journal")),
  );
  try {
    const { state: i, d } = await opened(owner);
    const commandId = randomUUID();
    const base = {
      profileId: i.profileId,
      sessionId: i.sessionId,
      commandId,
      presentationId: d.presentationId,
    };
    const request = {
      method: "practice.submission.prepare" as const,
      ...base,
      expectedRevision: d.revision,
      assistance: "NONE_DECLARED" as const,
    };
    const a = await s.request(request);
    assert.ok(a.ok && "evaluation" in a && a.evaluation);
    assert.equal(a.evaluation.stage, "PREPARED");
    const b = await s.request(request);
    assert.ok(b.ok && "evaluation" in b);
    assert.deepEqual(a.evaluation, b.evaluation);
    const conflict = await s.request({ ...request, assistance: "DECLARED" });
    assert.ok(!conflict.ok && conflict.code === "COMMAND_ID_CONFLICT");
    const saved = await owner.saveDraft(
      {
        presentationId: d.presentationId,
        expectedRevision: d.revision,
        source: "class LaterDraft {}",
        reflection: "PRIVATE-NOTES",
      },
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(saved.ok);
    await s.request({ method: "practice.submission.cancel", ...base });
    let result: PracticeResult = a;
    for (let n = 0; n < 100; n++) {
      result = await s.request({
        method: "practice.submission.status",
        ...base,
      });
      if (
        result.ok &&
        "evaluation" in result &&
        result.evaluation?.stage === "FINALIZED"
      )
        break;
      await new Promise((r) => setTimeout(r, 50));
    }
    assert.ok(result.ok && "evaluation" in result && result.evaluation);
    assert.equal(result.evaluation.outcome, "CANCELLED");
    assert.equal(result.evaluation.artifactSha256, a.evaluation.artifactSha256);
    assert.equal(JSON.stringify(result).includes("PRIVATE-NOTES"), false);
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
for (const after of [false, true])
  test(`lost prepare ${after ? "after" : "before"} commit reconciles without reconstructing source`, async () => {
    const dir = mkdtempSync(join(tmpdir(), "eval-lost-"));
    class Interrupted extends PracticeSession {
      override async request(c: PracticeCommand): Promise<PracticeResult> {
        if (c.method === "practice.submission.prepare") {
          if (after) {
            const r = await super.request(c);
            assert.ok(r.ok);
          }
          await this.stop();
          return { ok: false, code: "TIMEOUT" };
        }
        return super.request(c);
      }
    }
    const journal = new PracticeJournal(join(dir, "journal"));
    let owner = new PracticeOwner(
      () => new Interrupted(runtime(), join(dir, "p")),
      journal,
    );
    try {
      const { state, d } = await opened(owner);
      const r = await owner.prepareSubmission(
        {
          presentationId: d.presentationId,
          expectedRevision: d.revision,
          assistance: "UNKNOWN",
        },
        state.profileId,
        state.sessionId,
        () => true,
      );
      assert.ok(!r.ok);
      const metadata = readFileSync(
        join(dir, "journal/submissions/pending-submission.json"),
        "utf8",
      );
      assert.equal(metadata.includes("source"), false);
      assert.equal(metadata.includes("reflection"), false);
      await owner.disconnect();
      owner = new PracticeOwner(
        () => new PracticeSession(runtime(), join(dir, "p")),
        journal,
      );
      const fresh = await owner.connect(() => true);
      assert.ok(fresh.ok && fresh.pendingSubmission);
      const p = fresh.pendingSubmission;
      const got = await owner.submissionAction(
        {
          action: "status",
          commandId: p.commandId,
          presentationId: p.presentationId,
        },
        fresh.profileId,
        fresh.sessionId,
        () => true,
      );
      assert.ok(got.ok && "evaluation" in got);
      if (after) assert.equal(got.evaluation?.stage, "PREPARED");
      else assert.equal(got.evaluation, null);
      const next = await owner.connect(() => true);
      assert.ok(next.ok);
      assert.equal(next.pendingSubmission, null);
      const history = await owner.submissionsFor(
        p.presentationId,
        fresh.profileId,
        fresh.sessionId,
        () => true,
      );
      assert.ok(history.ok && history.method === "practice.submissions");
      assert.equal(history.submissions.length, after ? 1 : 0);
      assert.deepEqual(
        await owner.prepareSubmission(
          {},
          fresh.profileId,
          fresh.sessionId,
          () => false,
        ),
        { ok: false, code: "UNAVAILABLE" },
      );
    } finally {
      await owner.disconnect();
      rmSync(dir, { recursive: true, force: true });
    }
  });

test("late prepared response after disconnect cannot cross a new live session", async () => {
  const dir = mkdtempSync(join(tmpdir(), "eval-stale-"));
  let release!: () => void, entered!: () => void;
  const gate = new Promise<void>((r) => {
      release = r;
    }),
    ready = new Promise<void>((r) => {
      entered = r;
    });
  class Delayed extends PracticeSession {
    override async request(c: PracticeCommand): Promise<PracticeResult> {
      const result = await super.request(c);
      if (c.method === "practice.submission.prepare") {
        entered();
        await gate;
      }
      return result;
    }
  }
  const journal = new PracticeJournal(join(dir, "journal"));
  const owner = new PracticeOwner(
    () => new Delayed(runtime(), join(dir, "p")),
    journal,
  );
  try {
    const { state, d } = await opened(owner);
    const pending = owner.prepareSubmission(
      {
        presentationId: d.presentationId,
        expectedRevision: d.revision,
        assistance: "UNKNOWN",
      },
      state.profileId,
      state.sessionId,
      () => true,
    );
    await ready;
    await owner.disconnect();
    release();
    assert.deepEqual(await pending, { ok: false, code: "CANCELLED" });
    const next = await owner.connect(() => true);
    assert.ok(next.ok && next.pendingSubmission);
    assert.notEqual(next.sessionId, state.sessionId);
    const intent = next.pendingSubmission;
    const wrong = await owner.submissionAction(
      {
        action: "status",
        commandId: intent.commandId,
        presentationId: intent.presentationId,
      },
      next.profileId,
      state.sessionId,
      () => true,
    );
    assert.deepEqual(wrong, { ok: false, code: "SESSION_MISMATCH" });
    const right = await owner.submissionAction(
      {
        action: "status",
        commandId: intent.commandId,
        presentationId: intent.presentationId,
      },
      next.profileId,
      next.sessionId,
      () => true,
    );
    assert.ok(right.ok && "evaluation" in right && right.evaluation);
    assert.equal(right.evaluation.stage, "PREPARED");
  } finally {
    release();
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("hard-killed RUNNING Java owner recovers the same frozen artifact", async () => {
  const dir = mkdtempSync(join(tmpdir(), "eval-running-crash-"));
  let s = new PracticeSession(runtime(), join(dir, "p"));
  const owner = new PracticeOwner(
    () => s,
    new PracticeJournal(join(dir, "journal")),
  );
  try {
    const { state, d } = await opened(owner),
      commandId = randomUUID();
    let context = {
      profileId: state.profileId,
      sessionId: state.sessionId,
      presentationId: d.presentationId,
      commandId,
    };
    const prepared = await s.request({
      method: "practice.submission.prepare",
      ...context,
      expectedRevision: d.revision,
      assistance: "UNKNOWN",
    });
    assert.ok(prepared.ok && "evaluation" in prepared && prepared.evaluation);
    const digest = prepared.evaluation.artifactSha256;
    await s.request({ method: "practice.submission.resume", ...context });
    let running = false;
    for (let n = 0; n < 100; n++) {
      const r = await s.request({
        method: "practice.submission.status",
        ...context,
      });
      assert.ok(r.ok && "evaluation" in r && r.evaluation);
      if (r.evaluation.stage === "RUNNING") {
        running = true;
        break;
      }
      assert.notEqual(
        r.evaluation.stage,
        "FINALIZED",
        "Fixture must interrupt actual running work",
      );
      await new Promise((r) => setTimeout(r, 5));
    }
    assert.ok(running);
    process.kill(s.pid!, "SIGKILL");
    await s.stop();
    s = new PracticeSession(runtime(), join(dir, "p"));
    const fresh = await s.identity();
    context = { ...context, ...fresh };
    const recovered = await s.request({
      method: "practice.submission.status",
      ...context,
    });
    assert.ok(
      recovered.ok && "evaluation" in recovered && recovered.evaluation,
    );
    assert.equal(recovered.evaluation.artifactSha256, digest);
    await s.request({ method: "practice.submission.resume", ...context });
    let final: PracticeResult = recovered;
    for (let n = 0; n < 200; n++) {
      final = await s.request({
        method: "practice.submission.status",
        ...context,
      });
      if (
        final.ok &&
        "evaluation" in final &&
        final.evaluation?.stage === "FINALIZED"
      )
        break;
      await new Promise((r) => setTimeout(r, 50));
    }
    assert.ok(final.ok && "evaluation" in final && final.evaluation);
    assert.equal(final.evaluation.stage, "FINALIZED");
    assert.equal(final.evaluation.artifactSha256, digest);
    const again = await s.request({
      method: "practice.submission.resume",
      ...context,
    });
    assert.ok(again.ok && "evaluation" in again);
    assert.deepEqual(again.evaluation, final.evaluation);
  } finally {
    await s.stop();
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("debrief cache identity follows authoritative revisions and exact sessions, not object identity", () => {
  const r = SubmissionResultSchema.parse({
    commandId: randomUUID(),
    presentationId: `desktop-practice-${randomUUID()}`,
    draftRevision: 1,
    evaluationRevision: 1,
    assistance: "UNKNOWN",
    challengeId: "regression-localize",
    challengeVersion: 1,
    submittedAt: "2026-10-27T00:00:00Z",
    artifactSha256: "a".repeat(64),
    active: false,
    cancellationRequested: false,
    withdrawn: false,
    stage: "FINALIZED",
    outcome: "CANCELLED",
    checks: [],
  });
  const identity = { profileId: randomUUID(), sessionId: randomUUID() };
  const key = debriefIdentity(r, identity);
  assert.equal(debriefIdentity(structuredClone(r), { ...identity }), key);
  for (const changed of [
    { ...r, evaluationRevision: 2 }, // same outcome, genuine correction
    { ...r, withdrawn: true },
    { ...r, commandId: randomUUID() },
    { ...r, presentationId: `desktop-practice-${randomUUID()}` },
  ])
    assert.notEqual(debriefIdentity(changed, identity), key);
  assert.notEqual(
    debriefIdentity(r, { ...identity, sessionId: randomUUID() }),
    key,
  );
  assert.notEqual(
    debriefIdentity(r, { ...identity, profileId: randomUUID() }),
    key,
  );
});
