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
  DebriefInputSchema,
  DebriefSchema,
  PracticeWireSchema,
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
async function finalized(owner: PracticeOwner, session: PracticeSession) {
  const { state, d } = await opened(owner);
  const base = {
    profileId: state.profileId,
    sessionId: state.sessionId,
    commandId: randomUUID(),
    presentationId: d.presentationId,
  };
  assert.ok(
    (
      await session.request({
        method: "practice.submission.prepare",
        ...base,
        expectedRevision: d.revision,
        assistance: "UNKNOWN",
      })
    ).ok,
  );
  await session.request({ method: "practice.submission.cancel", ...base });
  for (let n = 0; n < 100; n++) {
    const r = await session.request({
      method: "practice.submission.status",
      ...base,
    });
    if (r.ok && "evaluation" in r && r.evaluation?.stage === "FINALIZED")
      return { state, base };
    await new Promise((r) => setTimeout(r, 40));
  }
  throw new Error("Finalization deadline");
}
test("real Java debrief, strict allowlists, identity and bounded responses", async () => {
  const dir = mkdtempSync(join(tmpdir(), "debrief-real-")),
    session = new PracticeSession(runtime(), join(dir, "p"));
  const owner = new PracticeOwner(
    () => session,
    new PracticeJournal(join(dir, "journal")),
  );
  try {
    const { state, base } = await finalized(owner, session);
    const input = {
      commandId: base.commandId,
      presentationId: base.presentationId,
    };
    const reply = await owner.readDebrief(
      input,
      state.profileId,
      state.sessionId,
      () => true,
    );
    assert.ok(reply.ok && "debrief" in reply, JSON.stringify(reply));
    const d = reply.debrief;
    assert.equal(d.evaluation.outcome, "CANCELLED");
    assert.equal(d.evidence.outcome, "UNAVAILABLE");
    assert.equal(d.currentEligibility, "NOT_EVALUATED");
    assert.equal(d.evidence.provenance, "LOCAL_CHECK");
    assert.ok(
      d.skills.length > 0 &&
        d.skills.every((s) => s.interpretation.status === "missing"),
    );
    for (const bad of [
      { ...d, source: "PRIVATE" },
      { ...d, readOnly: false },
      { ...d, currentEligibility: "ELIGIBLE" },
      { ...d, skills: Array(17).fill(d.skills[0]) },
      { ...d, limitations: "x".repeat(4097) },
      { ...d, checks: [{ ...d.checks[0], expectedOutput: "PRIVATE" }] },
      { ...d, evaluation: { ...d.evaluation, stage: "PREPARED" } },
      { ...d, checks: d.checks.map((c) => ({ ...c, status: "PASSED" })) },
      {
        ...d,
        skills: [
          {
            ...d.skills[0],
            interpretation: {
              ...d.skills[0].interpretation,
              band: "INDEPENDENT",
            },
          },
        ],
      },
    ])
      assert.equal(DebriefSchema.safeParse(bad).success, false);
    assert.equal(
      PracticeWireSchema.safeParse({
        ...reply,
        debrief: { ...d, privateReport: "PRIVATE" },
      }).success,
      false,
    );
    assert.deepEqual(
      await owner.readDebrief(
        { ...input, source: "PRIVATE" },
        state.profileId,
        state.sessionId,
        () => true,
      ),
      { ok: false, code: "INVALID_INPUT" },
    );
    assert.deepEqual(
      await owner.readDebrief(input, state.profileId, randomUUID(), () => true),
      { ok: false, code: "SESSION_MISMATCH" },
    );
    assert.deepEqual(
      await owner.readDebrief(
        input,
        state.profileId,
        state.sessionId,
        () => false,
      ),
      { ok: false, code: "UNAVAILABLE" },
    );
    for (const bad of [
      { ...input, commandId: "predict.v1" },
      { ...input, presentationId: "author-draft.v1" },
      { ...input, path: "/tmp/source" },
    ])
      assert.equal(DebriefInputSchema.safeParse(bad).success, false);
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
test("lost read and disconnect fence late debrief; restart retrieves original evidence", async () => {
  const dir = mkdtempSync(join(tmpdir(), "debrief-late-"));
  let release!: () => void,
    entered!: () => void,
    delay = false;
  const hold = new Promise<void>((r) => (release = r)),
    ready = new Promise<void>((r) => (entered = r));
  class Delayed extends PracticeSession {
    override async request(command: PracticeCommand): Promise<PracticeResult> {
      const result = await super.request(command);
      if (delay && command.method === "practice.submission.debrief") {
        entered();
        await hold;
      }
      return result;
    }
  }
  let session = new Delayed(runtime(), join(dir, "p"));
  const owner = new PracticeOwner(
    () => session,
    new PracticeJournal(join(dir, "journal")),
  );
  try {
    const { state, base } = await finalized(owner, session),
      input = {
        commandId: base.commandId,
        presentationId: base.presentationId,
      };
    delay = true;
    const pending = owner.readDebrief(
      input,
      state.profileId,
      state.sessionId,
      () => true,
    );
    await ready;
    await owner.disconnect();
    release();
    assert.deepEqual(await pending, { ok: false, code: "CANCELLED" });
    delay = false;
    session = new Delayed(runtime(), join(dir, "p"));
    const current = await owner.connect(() => true);
    assert.ok(current.ok);
    assert.notEqual(current.sessionId, state.sessionId);
    const read = await owner.readDebrief(
      input,
      current.profileId,
      current.sessionId,
      () => true,
    );
    assert.ok(read.ok && "debrief" in read);
    const again = await owner.readDebrief(
      input,
      current.profileId,
      current.sessionId,
      () => true,
    );
    assert.ok(again.ok && "debrief" in again);
    assert.equal(read.debrief.evidence.revision, 1);
    assert.deepEqual(read.debrief.evidence, again.debrief.evidence);
    assert.deepEqual(read.debrief.evaluation, again.debrief.evaluation);
  } finally {
    release();
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
