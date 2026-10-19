import { test } from "node:test";
import assert from "node:assert/strict";
import { EngineOwner } from "../electron/engine-owner";
import type { Result } from "../src/contract";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
const pass: Result = {
  ok: true,
  result: { source: "isolated-seed", readOnly: true, decks: [] },
};

test("cancelled old request cannot stop a newly started session", async () => {
  const oldReply = deferred<Result>();
  let creates = 0;
  let newStops = 0;
  const owner = new EngineOwner(() =>
    ++creates === 1
      ? { listDecks: () => oldReply.promise, stop: async () => {} }
      : {
          listDecks: async () => pass,
          stop: async () => {
            newStops++;
          },
        },
  );
  const original = owner.read(() => true);
  await owner.stop();
  assert.deepEqual(await owner.read(() => true), pass);
  oldReply.resolve({ ok: false, code: "CANCELLED" });
  assert.deepEqual(await original, { ok: false, code: "CANCELLED" });
  assert.equal(newStops, 0);
  await owner.shutdown();
  assert.equal(newStops, 1);
});

for (const mode of ["shutdown", "authorization lost"] as const)
  test(`no process is created after ${mode} during cleanup`, async () => {
    const cleanup = deferred<void>();
    let creates = 0;
    let authorized = true;
    const owner = new EngineOwner(() => {
      creates++;
      return { listDecks: async () => pass, stop: () => cleanup.promise };
    });
    await owner.read(() => true);
    const stopped = owner.stop();
    const pending = owner.read(() => authorized);
    const shutdown = mode === "shutdown" ? owner.shutdown() : Promise.resolve();
    authorized = false;
    cleanup.resolve();
    await stopped;
    await shutdown;
    assert.deepEqual(await pending, { ok: false, code: "UNAVAILABLE" });
    assert.equal(creates, 1);
  });

test("unauthorized calls never create a process; invalid results close their session", async () => {
  let creates = 0;
  let stops = 0;
  const owner = new EngineOwner(() => {
    creates++;
    return {
      listDecks: async () =>
        ({
          ok: true,
          result: { source: "learner", decks: [] },
        }) as unknown as Result,
      stop: async () => {
        stops++;
      },
    };
  });
  assert.deepEqual(await owner.read(() => false), {
    ok: false,
    code: "UNAVAILABLE",
  });
  assert.equal(creates, 0);
  assert.deepEqual(await owner.read(() => true), {
    ok: false,
    code: "START_FAILED",
  });
  assert.equal(stops, 1);
});
