import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { PracticeOwner } from "../electron/practice-owner";
import { PracticeSession } from "../electron/practice-session";
import { PracticeJournal } from "../electron/practice-journal";
import { runtimeLocation } from "../electron/java-session";
import { AnalyticsResponse, AnalyticsQuery } from "../src/analytics-contract";
import type { PracticeCommand, PracticeResult } from "../src/practice-contract";
import { seedAnalytics, databaseHashes } from "./analytics-fixture";
test("real analytics uses persisted Java history, strict metadata and zero table changes", async () => {
  const dir = mkdtempSync(join(tmpdir(), "analytics-node-"));
  seedAnalytics(join(dir, "p"));
  const owner = new PracticeOwner(
    () => new PracticeSession(runtimeLocation(resolve("java")), join(dir, "p")),
    new PracticeJournal(join(dir, "j")),
  );
  try {
    const i = await owner.connect(() => true);
    assert.ok(i.ok);
    const before = databaseHashes(join(dir, "p/codefit.db"));
    const r = await owner.analytics(
      { page: 0, snapshot: "" },
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(r.ok && r.method === "practice.analytics.read");
    assert.equal(r.revisions.length, 16);
    assert.equal(r.events.length, 4);
    assert.ok(
      r.revisions.some((x) => x.interpretation?.band === "INDEPENDENT"),
    );
    assert.ok(
      r.revisions.some((x) => x.warnings.includes("EVIDENCE_WITHDRAWN")),
    );
    assert.ok(AnalyticsResponse.safeParse(r).success);
    for (const bad of [
      { ...r, privateSource: "secret" },
      { ...r, page: 64 },
      { ...r, skills: [] },
      { ...r, events: r.events.map((e) => ({ ...e, outcome: "MASTERED" })) },
      {
        ...r,
        skills: r.skills.map((s) => ({ ...s, name: "x".repeat(600000) })),
      },
      { ...r, revisions: [...r.revisions, ...r.revisions] },
      { ...r, events: r.events.map((e) => ({ ...e, solution: "secret" })) },
      {
        ...r,
        revisions: r.revisions.map((e) => ({ ...e, status: "INCOMPATIBLE" })),
      },
    ])
      assert.equal(AnalyticsResponse.safeParse(bad).success, false);
    for (const q of [
      { page: -1, snapshot: "" },
      { page: 1, snapshot: "" },
      { page: 0, snapshot: "", sql: "SELECT" },
      { page: 0, snapshot: "x".repeat(600000) },
    ])
      assert.equal(AnalyticsQuery.safeParse(q).success, false);
    assert.deepEqual(
      await owner.analytics(
        { page: 0, snapshot: "" },
        i.profileId,
        "wrong",
        () => true,
      ),
      { ok: false, code: "SESSION_MISMATCH" },
    );
    await owner.analytics(
      { page: 0, snapshot: "" },
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.deepEqual(databaseHashes(join(dir, "p/codefit.db")), before);
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
test("analytics late replies never cross disconnect or replacement sessions", async () => {
  const dir = mkdtempSync(join(tmpdir(), "analytics-fence-"));
  let release!: () => void,
    entered!: () => void,
    delay = true;
  const held = new Promise<void>((r) => (release = r)),
    ready = new Promise<void>((r) => (entered = r));
  class Delayed extends PracticeSession {
    override async request(c: PracticeCommand): Promise<PracticeResult> {
      const r = await super.request(c);
      if (delay && c.method === "practice.analytics.read") {
        entered();
        await held;
      }
      return r;
    }
  }
  const owner = new PracticeOwner(
    () => new Delayed(runtimeLocation(resolve("java")), join(dir, "p")),
    new PracticeJournal(join(dir, "j")),
  );
  try {
    let i = await owner.connect(() => true);
    assert.ok(i.ok);
    const pending = owner.analytics(
      { page: 0, snapshot: "" },
      i.profileId,
      i.sessionId,
      () => true,
    );
    await ready;
    await owner.disconnect();
    release();
    assert.deepEqual(await pending, { ok: false, code: "CANCELLED" });
    delay = false;
    i = await owner.connect(() => true);
    assert.ok(i.ok);
    const r = await owner.analytics(
      { page: 0, snapshot: "" },
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(r.ok && r.method === "practice.analytics.read");
    assert.equal(r.revisions.length, 0);
  } finally {
    release?.();
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
