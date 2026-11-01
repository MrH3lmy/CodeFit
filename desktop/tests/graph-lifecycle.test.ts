import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import { randomUUID } from "node:crypto";
import { PracticeSession } from "../electron/practice-session";
import { runtimeLocation } from "../electron/java-session";
import { seedGraphs } from "./graph-fixture";
import { GraphResponse } from "../src/graph-contract";
test("exact Java selection replay, payload conflict and terminal unavailable versions survive process restart", async () => {
  const dir = mkdtempSync(join(tmpdir(), "graph-lifecycle-"));
  seedGraphs(dir);
  const runtime = runtimeLocation(resolve("java"));
  let s = new PracticeSession(runtime, dir);
  try {
    const i = await s.identity();
    const command = {
      method: "practice.graphs.select" as const,
      ...i,
      commandId: randomUUID(),
      originSessionId: i.sessionId,
      graphId: "java-debugging-concurrency",
      graphVersion: 2,
      selectionRevision: 0,
    };
    const saved = GraphResponse.parse(await s.request(command));
    assert.equal(saved.receipt?.state, "SELECTED");
    assert.deepEqual(
      GraphResponse.parse(await s.request(command)).receipt,
      saved.receipt,
    );
    assert.equal((await s.request({ ...command, graphVersion: 1 })).ok, false);
    await s.stop();
    s = new PracticeSession(runtime, dir);
    const next = await s.identity();
    assert.deepEqual(
      GraphResponse.parse(
        await s.request({
          ...command,
          ...next,
          method: "practice.graphs.status",
        }),
      ).receipt,
      saved.receipt,
    );
    for (const [graphId, graphVersion, reason] of [
      ["missing.graph", 1, "GRAPH_MISSING"],
      ["synthetic.retired", 1, "GRAPH_RETIRED"],
      ["synthetic.incompatible", 1, "RETIRED_SKILL"],
    ] as const) {
      const r = GraphResponse.parse(
        await s.request({
          ...command,
          ...next,
          commandId: randomUUID(),
          graphId,
          graphVersion,
          selectionRevision: 1,
        }),
      );
      assert.equal(r.receipt?.reason, reason);
    }
    const old = GraphResponse.parse(
      await s.request({
        ...command,
        ...next,
        commandId: randomUUID(),
        graphVersion: 1,
        selectionRevision: 0,
      }),
    );
    assert.equal(old.receipt?.reason, "SELECTION_CONFLICT");
  } finally {
    await s.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});
