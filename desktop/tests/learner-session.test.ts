import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import {
  LearnerOwner,
  LearnerSession,
  type LearnerReader,
} from "../electron/learner-session";
import { runtimeLocation } from "../electron/java-session";
import type { LearnerResult } from "../src/learner-contract";
import type { LearnerSkillsResult } from "../src/learner-contract";
import { LearnerSkillsSchema } from "../src/skill-contract";
import { createLearnerFixture } from "./learner-fixture";

test("real learner reads coalesce, preserve source bytes, and reopen frozen backups without domain writes", async () => {
  const dir = mkdtempSync(join(tmpdir(), "codefit-learner-test-")),
    file = join(dir, "source.db");
  createLearnerFixture(file);
  const before = readFileSync(file);
  const session = new LearnerSession(
    runtimeLocation(resolve("java")),
    file,
    join(dir, "snapshots"),
  );
  try {
    const first = session.read(),
      second = session.read();
    assert.equal(first, second);
    const result = await first;
    if (!result.ok) throw new Error(result.code);
    assert.deepEqual(result.result.progression, {
      status: "available",
      xp: 750,
      level: 8,
    });
    assert.equal(result.result.identity.displayName, "Ada Learner");
    assert.equal(result.result.activity.reviewCount, 4);
    assert.equal(result.result.activity.dueScheduledCards, 3);
    assert.equal(result.result.mastery.status, "unavailable");
    assert.deepEqual(await session.read(), result);
    assert.deepEqual(readFileSync(file), before);
    assert.ok(!JSON.stringify(result).includes("PRIVATE-"));
    assert.ok(!JSON.stringify(result).includes(file));
  } finally {
    await session.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("mixed overview and skill reads serialize and coalesce without leaking private metadata", async () => {
  const dir = mkdtempSync(join(tmpdir(), "codefit-skills-")),
    file = join(dir, "source.db");
  createLearnerFixture(file, "ascension-supported");
  const before = readFileSync(file);
  const session = new LearnerSession(
    runtimeLocation(resolve("java")),
    file,
    join(dir, "snapshots"),
  );
  try {
    const first = session.readSkills(),
      duplicate = session.readSkills(),
      overview = session.read();
    assert.equal(first, duplicate);
    const [a, b] = await Promise.all([first, overview]);
    assert.ok(a.ok);
    assert.ok(b.ok);
    if (!a.ok || !b.ok) return;
    assert.equal(a.result.kind, "learner-skills");
    assert.equal(b.result.kind, "learner-overview");
    assert.equal(a.result.sessionId, b.result.sessionId);
    assert.equal(a.result.capturedAt, b.result.capturedAt);
    assert.ok(a.result.nodes.some((n) => n.estimate.band === "INDEPENDENT"));
    assert.ok(
      a.result.nodes.every(
        (n) => n.estimate.currentSupport === "NOT_EVALUATED",
      ),
    );
    assert.deepEqual(await session.readSkills(), a);
    assert.ok(!JSON.stringify(a).includes("PRIVATE-"));
    assert.deepEqual(readFileSync(file), before);
    const corrupt = structuredClone(a.result);
    (corrupt.nodes[0] as any).answer = "secret";
    assert.equal(LearnerSkillsSchema.safeParse(corrupt).success, false);
    const mismatch = structuredClone(a.result);
    mismatch.edges[0].prerequisite.version++;
    assert.equal(LearnerSkillsSchema.safeParse(mismatch).success, false);
    const cycle = structuredClone(a.result);
    cycle.edges.push({
      prerequisite: cycle.edges[0].dependent,
      dependent: cycle.edges[0].prerequisite,
    });
    assert.equal(LearnerSkillsSchema.safeParse(cycle).success, false);
    const excessive = structuredClone(a.result);
    excessive.nodes = Array.from({ length: 33 }, () => a.result.nodes[0]);
    assert.equal(LearnerSkillsSchema.safeParse(excessive).success, false);
  } finally {
    await session.stop();
    rmSync(dir, { recursive: true, force: true });
  }
});

test("profile switch fences a delayed skill read without disconnecting the replacement", async () => {
  let release!: (v: LearnerSkillsResult) => void;
  const waiting = new Promise<LearnerSkillsResult>((r) => {
    release = r;
  });
  const overview = { ok: true, result: {} } as LearnerResult;
  const owner = new LearnerOwner(
    () => {
      throw new Error();
    },
    "/unused",
    (source) => ({
      read: async () => overview,
      readSkills: () =>
        source === "A"
          ? waiting
          : Promise.resolve({ ok: false, code: "READ_FAILED" }),
      stop: async () => {},
    }),
  );
  await owner.connect("A", () => true);
  const old = owner.readSkills(() => true);
  await owner.connect("B", () => true);
  release({ ok: false, code: "READ_FAILED" });
  assert.deepEqual(await old, { ok: false, code: "CANCELLED" });
  assert.deepEqual(await owner.read(() => true), overview);
  assert.deepEqual(await owner.readSkills(() => false), {
    ok: false,
    code: "UNAVAILABLE",
  });
  await owner.shutdown();
});
test("unsupported schema and missing facts are explicit with source unchanged", async () => {
  for (const mode of ["future", "older", "missing"]) {
    const dir = mkdtempSync(join(tmpdir(), "codefit-learner-invalid-")),
      file = join(dir, "source.db");
    createLearnerFixture(file, mode);
    const before = readFileSync(file);
    const session = new LearnerSession(
      runtimeLocation(resolve("java")),
      file,
      join(dir, "snapshots"),
    );
    try {
      const result = await session.read();
      if (mode === "missing") {
        assert.equal(result.ok, true);
        if (result.ok)
          assert.equal(result.result.progression.status, "unavailable");
      } else
        assert.deepEqual(result, { ok: false, code: "INCOMPATIBLE_SCHEMA" });
      assert.deepEqual(readFileSync(file), before);
    } finally {
      await session.stop();
      rmSync(dir, { recursive: true, force: true });
    }
  }
});

const profile = "a".repeat(64);
async function pipe(
  source: string,
  check: (s: LearnerSession) => Promise<void>,
  deadline = 500,
) {
  const dir = mkdtempSync(join(tmpdir(), "codefit-learner-pipe-")),
    file = join(dir, "pipe");
  writeFileSync(
    file,
    `#!${process.execPath}\nconst session=process.argv[process.argv.indexOf('--session')+1];\n${source}`,
    { mode: 0o700 },
  );
  const s = new LearnerSession(
    { java: file, classpath: "unused", cwd: dir },
    "/never-opened",
    join(dir, "snapshots"),
    deadline,
  );
  try {
    await check(s);
  } finally {
    await s.stop();
    rmSync(dir, { recursive: true, force: true });
  }
}
const ready = `process.stdout.write(JSON.stringify({v:3,type:'ready',profileId:'${profile}',sessionId:session})+'\\n');`;
const emptySkillReply = `{v:1,kind:'learner-skills',source:'learner-snapshot',profileId:'${profile}',sessionId:session,capturedAt:'2026-01-01T00:00:00Z',schemaVersion:25,readOnly:true,status:'unavailable',reason:'GRAPH_UNAVAILABLE',graph:null,newerGraphAvailable:false,nodes:[],edges:[]}`;
test("skill transport enforces method correlation, profile identity, private-field and frame bounds", async () => {
  for (const mutation of [
    "result.profileId='b'.repeat(64);",
    "result.answer='PRIVATE-ANSWER';",
    "result.nodes=Array(33).fill({});",
  ]) {
    await pipe(
      `${ready}require('node:readline').createInterface({input:process.stdin}).on('line',line=>{const req=JSON.parse(line),result=${emptySkillReply};${mutation}process.stdout.write(JSON.stringify({v:3,id:req.id,ok:true,result})+'\\n');});`,
      async (s) =>
        assert.deepEqual(await s.readSkills(), {
          ok: false,
          code: "PROTOCOL_ERROR",
        }),
    );
  }
  await pipe(
    `${ready}process.stdin.once('data',()=>process.stdout.write('x'.repeat(262145)));`,
    async (s) =>
      assert.deepEqual(await s.readSkills(), {
        ok: false,
        code: "PROTOCOL_ERROR",
      }),
  );
  await pipe(
    `${ready}require('node:readline').createInterface({input:process.stdin}).on('line',line=>{const req=JSON.parse(line);process.stdout.write(JSON.stringify({v:3,id:req.id,ok:true,result:${emptySkillReply}})+'\\n');});`,
    async (s) =>
      assert.deepEqual(await s.read(), { ok: false, code: "PROTOCOL_ERROR" }),
  );
});
test("cancellation stops a skill read and its queued overview without restarting a process", async () => {
  await pipe(`${ready}process.stdin.resume();`, async (s) => {
    const skills = s.readSkills(),
      overview = s.read();
    await s.stop();
    assert.deepEqual(await skills, { ok: false, code: "CANCELLED" });
    assert.deepEqual(await overview, { ok: false, code: "CANCELLED" });
    assert.deepEqual(await s.readSkills(), { ok: false, code: "CANCELLED" });
  });
  await pipe(
    `${ready}process.stdin.resume();`,
    async (s) =>
      assert.deepEqual(await s.readSkills(), { ok: false, code: "TIMEOUT" }),
    100,
  );
});
for (const [name, source] of Object.entries({
  "duplicate key": `process.stdout.write('{"v":3,"v":3,"type":"ready"}\\n');process.stdin.resume();`,
  "invalid UTF8": `process.stdout.write(Buffer.from([255,10]));process.stdin.resume();`,
  BOM: `process.stdout.write(Buffer.from([239,187,191]));${ready}process.stdin.resume();`,
  "wrong session": `process.stdout.write(JSON.stringify({v:3,type:'ready',profileId:'${profile}',sessionId:'8f12a1b0-56c1-4c3e-a111-3495bbce64a0'})+'\\n');process.stdin.resume();`,
  "oversized frame": `process.stdout.write('x'.repeat(8193));process.stdin.resume();`,
  "private field": `process.stdout.write(JSON.stringify({v:3,type:'ready',profileId:'${profile}',sessionId:session,answer:'private'})+'\\n');process.stdin.resume();`,
}))
  test(`learner transport rejects ${name}`, async () => {
    await pipe(source, async (s) =>
      assert.deepEqual(await s.read(), { ok: false, code: "PROTOCOL_ERROR" }),
    );
  });
test("learner startup deadline and cancellation terminate unresponsive native-worker substitutes", async () => {
  await pipe(
    `process.stdin.resume();process.on('SIGTERM',()=>{});setInterval(()=>{},1000);`,
    async (s) =>
      assert.deepEqual(await s.read(), { ok: false, code: "TIMEOUT" }),
    100,
  );
  await pipe(`process.stdin.resume();`, async (s) => {
    const read = s.read();
    await s.stop();
    assert.deepEqual(await read, { ok: false, code: "CANCELLED" });
  });
});
test("runtime resolution is lazy and unauthorized or failed selection never starts a reader", async () => {
  let calls = 0;
  const owner = new LearnerOwner(() => {
    calls++;
    throw new Error("missing");
  }, "/unused");
  assert.equal(calls, 0);
  assert.deepEqual(await owner.connect("/unused", () => false), {
    ok: false,
    code: "UNAVAILABLE",
  });
  assert.deepEqual(await owner.connect("/unused", () => true), {
    ok: false,
    code: "START_FAILED",
  });
  assert.equal(calls, 1);
  await owner.shutdown();
});
test("switching and disconnect invalidate an older delayed read without stopping the replacement", async () => {
  let release!: (v: LearnerResult) => void,
    stopped = 0;
  const delayed = new Promise<LearnerResult>((r) => {
    release = r;
  });
  const owner = new LearnerOwner(
    () => {
      throw new Error();
    },
    "/unused",
    (source): LearnerReader => ({
      read: () =>
        source === "A"
          ? delayed
          : Promise.resolve({ ok: false, code: "INCOMPATIBLE_SCHEMA" }),
      readSkills: async () => ({ ok: false, code: "UNAVAILABLE" }),
      stop: async () => {
        stopped++;
      },
    }),
  );
  const a = owner.connect("A", () => true);
  await new Promise((r) => setImmediate(r));
  assert.deepEqual(await owner.connect("B", () => true), {
    ok: false,
    code: "INCOMPATIBLE_SCHEMA",
  });
  release({ ok: false, code: "READ_FAILED" });
  assert.deepEqual(await a, { ok: false, code: "CANCELLED" });
  assert.equal(stopped, 2);
  await owner.shutdown();
  assert.deepEqual(await owner.read(() => true), {
    ok: false,
    code: "UNAVAILABLE",
  });
});
