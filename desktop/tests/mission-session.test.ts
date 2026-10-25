import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import {
  LearnerOwner,
  LearnerSession,
  type LearnerReader,
} from "../electron/learner-session";
import { runtimeLocation } from "../electron/java-session";
import { LearnerMissionsSchema } from "../src/mission-contract";
import type { LearnerMissionsResult } from "../src/learner-contract";
import { createLearnerFixture } from "./learner-fixture";

test("real mission read coalesces and serializes with existing snapshot reads without source mutation", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mission-session-")),
    source = join(dir, "learner.db");
  createLearnerFixture(source, "mission-recorded");
  const before = readFileSync(source);
  const session = new LearnerSession(
    runtimeLocation(resolve("java")),
    source,
    join(dir, "snapshots"),
  );
  try {
    const one = session.readMissions(),
      duplicate = session.readMissions();
    assert.equal(one, duplicate);
    const [missions, overview, skills] = await Promise.all([
      one,
      session.read(),
      session.readSkills(),
    ]);
    assert.ok(missions.ok && overview.ok && skills.ok);
    if (!missions.ok || !overview.ok || !skills.ok) return;
    assert.equal(missions.result.sessionId, overview.result.sessionId);
    assert.equal(missions.result.profileId, skills.result.profileId);
    assert.equal(missions.result.currentEligibility, "NOT_EVALUATED");
    assert.equal(missions.result.actionsEnabled, false);
    assert.equal(missions.result.activities.length, 3);
    assert.ok(missions.result.activities.some((a) => a.outcome === "PASSED"));
    assert.ok(!JSON.stringify(missions).includes("PRIVATE-"));
    assert.ok(!JSON.stringify(missions).includes("java.general."));
    assert.deepEqual(await session.readMissions(), missions);
    assert.deepEqual(readFileSync(source), before);
    for (const modify of [
      (p: any) => (p.actionsEnabled = true),
      (p: any) => (p.activities[2].evaluatedAt = null),
      (p: any) => (p.activities[2].outcome = null),
      (p: any) => (p.review.asOfDay = "1999-01-01"),
      (p: any) => (p.activities[0].artifact = "PRIVATE-SOURCE"),
      (p: any) => (p.activities[0].stage = "ACTIVE"),
      (p: any) => (p.currentEligibility = "ELIGIBLE"),
      (p: any) => (p.activities = Array(21).fill(p.activities[0])),
      (p: any) => (p.quests[0].prompt = "PRIVATE"),
      (p: any) => (p.activities[0].metadata = "APPROVED"),
      (p: any) => (p.activities[0].questVersion = 1.5),
      (p: any) => (p.activities[0].submittedAt = "2999-01-01T00:00:00Z"),
    ]) {
      const corrupt: any = structuredClone(missions.result);
      modify(corrupt);
      assert.equal(LearnerMissionsSchema.safeParse(corrupt).success, false);
    }
  } finally {
    await session.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("corrupt history stays unavailable and empty history is honest across real snapshot restart", async () => {
  const dir = mkdtempSync(join(tmpdir(), "mission-states-"));
  try {
    for (const mode of ["empty", "corrupt"]) {
      const source = join(dir, `${mode}.db`);
      createLearnerFixture(source, `mission-${mode}`);
      const before = readFileSync(source);
      for (let i = 0; i < 2; i++) {
        const s = new LearnerSession(
          runtimeLocation(resolve("java")),
          source,
          join(dir, "snapshots"),
        );
        try {
          const r = await s.readMissions();
          assert.ok(r.ok);
          if (!r.ok) return;
          assert.equal(
            r.result.historyStatus,
            mode === "empty" ? "AVAILABLE" : "UNAVAILABLE",
          );
          assert.equal(r.result.activities.length, 0);
        } finally {
          await s.stop();
        }
      }
      assert.deepEqual(readFileSync(source), before);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("mission profile switch and disconnect fence delayed responses without stopping replacement", async () => {
  let finish!: (r: LearnerMissionsResult) => void;
  let stoppedB = false;
  const wait = new Promise<LearnerMissionsResult>((r) => (finish = r));
  const owner = new LearnerOwner(
    () => {
      throw Error();
    },
    "unused",
    (source): LearnerReader => ({
      read: async () => ({ ok: true, result: {} as any }),
      readSkills: async () => ({ ok: false, code: "UNAVAILABLE" }),
      readMissions: () =>
        source === "A"
          ? wait
          : Promise.resolve({ ok: false, code: "READ_FAILED" }),
      stop: async () => {
        if (source === "B") stoppedB = true;
      },
    }),
  );
  await owner.connect("A", () => true);
  const old = owner.readMissions(() => true);
  await owner.connect("B", () => true);
  finish({ ok: false, code: "READ_FAILED" });
  assert.deepEqual(await old, { ok: false, code: "CANCELLED" });
  assert.equal(stoppedB, false);
  assert.deepEqual(await owner.readMissions(() => false), {
    ok: false,
    code: "UNAVAILABLE",
  });
  assert.equal(stoppedB, false);
  await owner.disconnect();
  assert.equal(stoppedB, true);
});
