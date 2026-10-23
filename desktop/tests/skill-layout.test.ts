import { test } from "node:test";
import assert from "node:assert/strict";
import { spatialNeighbor, layoutGraph } from "../src/skill-layout";
import { skills } from "../src/data";

test("spatial arrow navigation follows direction, remains at edges and is deterministic", () => {
  assert.equal(
    spatialNeighbor(skills, "debug", "ArrowRight")?.id,
    "collections",
  );
  assert.equal(spatialNeighbor(skills, "debug", "ArrowDown")?.id, "flow");
  assert.equal(spatialNeighbor(skills, "core", "ArrowDown"), undefined);
  assert.equal(
    spatialNeighbor(skills, "collections", "ArrowUp")?.id,
    "concurrency",
  );
  assert.equal(
    spatialNeighbor([...skills].reverse(), "debug", "ArrowRight")?.id,
    "collections",
  );
  assert.equal(spatialNeighbor(skills, "debug", "Home")?.id, skills[0].id);
  assert.equal(spatialNeighbor(skills, "debug", "End")?.id, skills.at(-1)!.id);
});
test("domain membership is explicit and layout uses actual edges, including disconnected nodes", () => {
  assert.ok(skills.every((s) => s.branches.length));
  const nodes = ["a", "b", "c", "d"].map((id) => ({ ref: { id } }));
  const edges = [
    { prerequisite: { id: "a" }, dependent: { id: "c" } },
    { prerequisite: { id: "b" }, dependent: { id: "c" } },
  ];
  const graph = layoutGraph(nodes, edges),
    point = (id: string) => graph.points.find((p) => p.id === id)!;
  assert.ok(point("c").y < point("a").y);
  assert.equal(point("a").y, point("b").y);
  assert.notEqual(point("a").x, point("b").x);
  assert.equal(point("d").y, point("a").y);
  assert.throws(() =>
    layoutGraph(nodes, [
      ...edges,
      { prerequisite: { id: "c" }, dependent: { id: "a" } },
    ]),
  );
});
