import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { PracticeOwner } from "../electron/practice-owner";
import { PracticeSession } from "../electron/practice-session";
import { PracticeJournal } from "../electron/practice-journal";
import { runtimeLocation } from "../electron/java-session";
import { GraphActionSchema, GraphResponse } from "../src/graph-contract";
test("real Java catalog read, explicit selection receipt and exact replay recovery", async () => {
  const dir = mkdtempSync(join(tmpdir(), "graphs-owner-"));
  const owner = new PracticeOwner(
    () => new PracticeSession(runtimeLocation(resolve("java")), join(dir, "p")),
    new PracticeJournal(join(dir, "j")),
  );
  try {
    const i = await owner.connect(() => true);
    assert.ok(i.ok);
    const initial = await owner.graphAction(
      { kind: "read", graphId: "", graphVersion: 0 },
      i.profileId,
      i.sessionId,
      () => true,
    );
    assert.ok(initial.ok, JSON.stringify(initial));
    const read = GraphResponse.parse(initial);
    assert.equal(read.view?.graphs.length, 1);
    assert.equal(read.view?.detail?.skills.length, 4);
    assert.equal(read.view?.selected.graphVersion, 1);
    assert.equal(read.view?.graphs[0].contentReview, "NOT_RECORDED");
    assert.equal(
      GraphResponse.safeParse({ ...read, privateSource: "secret" }).success,
      false,
    );
    assert.equal(
      GraphActionSchema.safeParse({
        kind: "select",
        graphId: "java-debugging-concurrency",
        graphVersion: 1,
        selectionRevision: 0,
        sql: "arbitrary",
      }).success,
      false,
    );
    const receipt = GraphResponse.parse(
      await owner.graphAction(
        {
          kind: "select",
          graphId: "java-debugging-concurrency",
          graphVersion: 1,
          selectionRevision: 0,
        },
        i.profileId,
        i.sessionId,
        () => true,
      ),
    );
    assert.equal(receipt.receipt?.state, "UNCHANGED");
    assert.equal(receipt.pending, null);
    const rejected = GraphResponse.parse(
      await owner.graphAction(
        {
          kind: "select",
          graphId: "unreviewed.stage",
          graphVersion: 1,
          selectionRevision: 0,
        },
        i.profileId,
        i.sessionId,
        () => true,
      ),
    );
    assert.equal(rejected.receipt?.reason, "GRAPH_MISSING");
    const denied = await owner.graphAction(
      { kind: "read", graphId: "", graphVersion: 0 },
      i.profileId,
      i.sessionId,
      () => false,
    );
    assert.equal(denied.ok, false);
  } finally {
    await owner.disconnect();
    rmSync(dir, { recursive: true, force: true });
  }
});
