import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { PracticeSession } from "../electron/practice-session";
import { PracticeOwner } from "../electron/practice-owner";
import { PracticeJournal } from "../electron/practice-journal";
import { runtimeLocation } from "../electron/java-session";
import {
  PracticeCommandSchema,
  PracticeRequestSchema,
  type PracticeCommand,
  type PracticeResult,
} from "../src/practice-contract";
import { SkillsStateResponse } from "../src/practice-skills-contract";
import { randomUUID } from "node:crypto";
const runtime = () => runtimeLocation(resolve("java"));
test("real Java skill publication, exact receipt replay, historical reads and strict schemas", async () => {
  const dir = mkdtempSync(join(tmpdir(), "skills-owner-"));
  const owner = new PracticeOwner(
    () => new PracticeSession(runtime(), join(dir, "p")),
    new PracticeJournal(join(dir, "j")),
  );
  try {
    const i = await owner.connect(() => true);
    assert.ok(i.ok);
    const first = await owner.skillAction(
      "read",
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(first.ok && first.method === "practice.skills.state");
    assert.equal(first.reconciliation, null);
    assert.equal(first.catalog.length, 4);
    const saved = await owner.skillAction(
      "publish",
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(
      saved.ok &&
        saved.method === "practice.skills.reconcile" &&
        saved.reconciliation,
    );
    assert.equal(saved.reconciliation.state, "PUBLISHED");
    assert.ok(
      saved.reconciliation.skills.every(
        (s) => s.band === "UNKNOWN" && s.support === "INSUFFICIENT",
      ),
    );
    for (let n = 0; n < 3; n++) {
      const r = await owner.skillAction(
        "read",
        i.profileId,
        i.sessionId,
        () => true,
      );
      assert.ok(r.ok && "reconciliation" in r);
      assert.deepEqual(r.reconciliation, saved.reconciliation);
    }
    assert.equal(
      SkillsStateResponse.safeParse({ ...first, source: "secret" }).success,
      false,
    );
    for (const extra of [
      { asOf: "2030-01-01T00:00:00Z" },
      { graphId: "author-draft.v1" },
      { sql: "select 1" },
    ])
      assert.equal(
        PracticeCommandSchema.safeParse({
          method: "practice.skills.state",
          profileId: i.profileId,
          sessionId: i.sessionId,
          ...extra,
        }).success,
        false,
      );
    assert.equal(
      PracticeRequestSchema.safeParse({
        v: 1,
        id: randomUUID(),
        method: "practice.skills.reconcile",
        profileId: i.profileId,
        sessionId: i.sessionId,
        commandId: randomUUID(),
        originSessionId: i.sessionId,
        policy: "fake",
      }).success,
      false,
    );
    await owner.disconnect();
    const next = await owner.connect(() => true);
    assert.ok(next.ok);
    assert.notEqual(next.sessionId, i.sessionId);
    const r = await owner.skillAction(
      "read",
      next.profileId,
      next.sessionId,
      () => true,
    );
    assert.ok(r.ok && "reconciliation" in r);
    assert.deepEqual(r.reconciliation, saved.reconciliation);
    assert.deepEqual(
      await owner.skillAction(
        "publish",
        next.profileId,
        i.sessionId,
        () => true,
      ),
      { ok: false, code: "SESSION_MISMATCH" },
    );
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
for (const afterCommit of [false, true])
  test(`lost skill ${afterCommit ? "committed" : "undispatched"} command reconciles without automatic publication`, async () => {
    const dir = mkdtempSync(join(tmpdir(), "skills-lost-"));
    let lose = true;
    class Lost extends PracticeSession {
      override async request(c: PracticeCommand): Promise<PracticeResult> {
        if (c.method === "practice.skills.reconcile" && lose) {
          lose = false;
          if (afterCommit) await super.request(c);
          return { ok: false, code: "TIMEOUT" };
        }
        return super.request(c);
      }
    }
    const owner = new PracticeOwner(
      () => new Lost(runtime(), join(dir, "p")),
      new PracticeJournal(join(dir, "j")),
    );
    try {
      let i = await owner.connect(() => true);
      assert.ok(i.ok);
      assert.deepEqual(
        await owner.skillAction(
          "publish",
          i.profileId,
          i.sessionId,
          () => true,
        ),
        { ok: false, code: "TIMEOUT" },
      );
      const pending = JSON.parse(
        readFileSync(join(dir, "j/skills/pending-skills.json"), "utf8"),
      );
      assert.deepEqual(Object.keys(pending).sort(), [
        "commandId",
        "operation",
        "originSessionId",
        "profileId",
        "v",
      ]);
      await owner.disconnect();
      i = await owner.connect(() => true);
      assert.ok(i.ok);
      const recovered = await owner.skillAction(
        "read",
        i.profileId,
        i.sessionId,
        () => true,
      );
      assert.ok(recovered.ok && recovered.method === "practice.skills.status");
      assert.equal(recovered.commandId, pending.commandId);
      assert.equal(
        recovered.reconciliation?.state ?? null,
        afterCommit ? "PUBLISHED" : null,
      );
      const again = await owner.skillAction(
        "read",
        i.profileId,
        i.sessionId,
        () => true,
      );
      assert.ok(again.ok && "reconciliation" in again);
      assert.deepEqual(again.reconciliation, recovered.reconciliation);
    } finally {
      await owner.disconnect();
      rmSync(dir, { recursive: true, force: true });
    }
  });

test("pending skill reply cannot cross disconnect and recovery uses original receipt", async () => {
  const dir = mkdtempSync(join(tmpdir(), "skills-fence-"));
  let release!: () => void,
    entered!: () => void,
    delay = true;
  const held = new Promise<void>((r) => (release = r)),
    ready = new Promise<void>((r) => (entered = r));
  class Delayed extends PracticeSession {
    override async request(c: PracticeCommand): Promise<PracticeResult> {
      const r = await super.request(c);
      if (delay && c.method === "practice.skills.reconcile") {
        entered();
        await held;
      }
      return r;
    }
  }
  const owner = new PracticeOwner(
    () => new Delayed(runtime(), join(dir, "p")),
    new PracticeJournal(join(dir, "j")),
  );
  try {
    let i = await owner.connect(() => true);
    assert.ok(i.ok);
    const publishing = owner.skillAction(
      "publish",
      i.profileId,
      i.sessionId,
      () => true,
    );
    await ready;
    await owner.disconnect();
    release();
    assert.deepEqual(await publishing, { ok: false, code: "CANCELLED" });
    delay = false;
    i = await owner.connect(() => true);
    assert.ok(i.ok);
    const r = await owner.skillAction(
      "read",
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(
      r.ok && r.method === "practice.skills.status" && r.reconciliation,
    );
    assert.equal(r.reconciliation.state, "PUBLISHED");
  } finally {
    release?.();
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
