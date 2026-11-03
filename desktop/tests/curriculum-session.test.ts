import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtempSync,
  writeFileSync,
  readFileSync,
  readdirSync,
  rmSync,
  cpSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve, delimiter } from "node:path";
import { createHash } from "node:crypto";
import {
  CurriculumOwner,
  CurriculumSession,
  type CurriculumReader,
} from "../electron/curriculum-session";
import { runtimeLocation } from "../electron/java-session";
import {
  CurriculumResultSchema,
  COVERAGE_LEDGER_SHA,
  PUBLIC_COVERAGE_SHA,
  PUBLIC_PREVIEW_SHA,
  type CurriculumResult,
} from "../src/curriculum-contract";

async function real() {
  const session = new CurriculumSession(runtimeLocation(resolve("java")));
  try {
    return await session.read();
  } finally {
    await session.stop();
  }
}
async function fixture(
  source: string,
  check: (s: CurriculumSession, dir: string) => Promise<void>,
  deadline = 2000,
) {
  const dir = mkdtempSync(join(tmpdir(), "atlas-pipe-"));
  const exe = join(dir, "fixture");
  writeFileSync(exe, `#!${process.execPath}\n${source}`, { mode: 0o700 });
  const s = new CurriculumSession(
    { java: exe, classpath: "unused", cwd: dir },
    deadline,
  );
  try {
    await check(s, dir);
  } finally {
    await s.stop();
    rmSync(dir, { recursive: true, force: true });
  }
}
const onRead =
  "let data=''; process.stdin.on('data', b => data += b); process.stdin.on('end', () => { const req=JSON.parse(data);";

test("real pinned Java read needs no database, survives restart and strips injection environment", async () => {
  const dir = mkdtempSync(join(tmpdir(), "atlas-real-"));
  writeFileSync(join(dir, "codefit.db"), "PRIVATE-LEARNER-SENTINEL");
  const before = readdirSync(dir);
  const runtime = { ...runtimeLocation(resolve("java")), cwd: dir };
  const old = process.env.JAVA_TOOL_OPTIONS;
  process.env.JAVA_TOOL_OPTIONS = "-invalid";
  try {
    let last: CurriculumResult | undefined;
    for (let n = 0; n < 2; n++) {
      const s = new CurriculumSession(runtime);
      try {
        const a = s.read(),
          b = s.read();
        assert.equal(a, b);
        const result = await a;
        assert.ok(result.ok);
        if (!result.ok) return;
        const p = result.result.preview;
        assert.equal(
          createHash("sha256").update(JSON.stringify(p)).digest("hex"),
          PUBLIC_PREVIEW_SHA,
        );
        assert.equal(p.skills.length, 4);
        assert.equal(p.blockers.length, 10);
        const coverage = result.result.coverage;
        assert.equal(coverage.ledgerSha256, COVERAGE_LEDGER_SHA);
        assert.equal(
          createHash("sha256").update(JSON.stringify(coverage)).digest("hex"),
          PUBLIC_COVERAGE_SHA,
        );
        assert.equal(coverage.books.length, 5);
        assert.equal(coverage.reportedClaims.structuralUnits, 504);
        assert.equal(coverage.reportedClaims.preliminaryMappedUnits, 157);
        assert.equal(coverage.reportedClaims.reportedUnmappedUnits, 347);
        assert.equal(coverage.reportedClaims.missionBlueprintSlots, 419);
        assert.equal(coverage.reportedClaims.revisionEvents.count, 424);
        assert.equal(
          coverage.reportedClaims.revisionEvents.additiveToMissionBlueprintSlots,
          false,
        );
        assert.ok(coverage.books.every((b) =>
          b.preliminaryMapped.status === "UNKNOWN" &&
          b.preliminaryMapped.count === null));
        assert.equal(coverage.learnerValidated.status, "UNKNOWN");
        assert.ok(!JSON.stringify(result).includes("PRIVATE-"));
        assert.ok(!JSON.stringify(result).includes("private_checkpoint"));
        if (last) assert.deepEqual(result, last);
        last = result;
      } finally {
        await s.stop();
      }
    }
    assert.deepEqual(readdirSync(dir), before);
    assert.equal(
      readFileSync(join(dir, "codefit.db"), "utf8"),
      "PRIVATE-LEARNER-SENTINEL",
    );
  } finally {
    if (old === undefined) delete process.env.JAVA_TOOL_OPTIONS;
    else process.env.JAVA_TOOL_OPTIONS = old;
    rmSync(dir, { recursive: true, force: true });
  }
});

for (const mode of ["missing", "corrupt"] as const)
  test(`${mode} bundled catalog fails closed through the real Java process`, async () => {
    const dir = mkdtempSync(join(tmpdir(), "atlas-resource-"));
    cpSync(resolve("java/engine/classes"), join(dir, "classes"), {
      recursive: true,
    });
    const file = join(
      dir,
      "classes/knowledge/hunter-pilot-v0.41/preview-catalog.json",
    );
    if (mode === "missing") rmSync(file);
    else writeFileSync(file, "{}");
    const s = new CurriculumSession({
      ...runtimeLocation(resolve("java")),
      cwd: dir,
      classpath: [join(dir, "classes"), resolve("java/engine/lib/*")].join(
        delimiter,
      ),
    });
    try {
      assert.deepEqual(await s.read(), { ok: false, code: "INVALID_CATALOG" });
    } finally {
      await s.stop();
      rmSync(dir, { recursive: true, force: true });
    }
  });

for (const mode of ["missing", "malformed", "stale", "source mismatch"] as const)
  test(`${mode} coverage metadata fails closed without learner writes`, async () => {
    const dir = mkdtempSync(join(tmpdir(), "atlas-coverage-"));
    cpSync(resolve("java/engine/classes"), join(dir, "classes"), {
      recursive: true,
    });
    const file = join(
      dir,
      "classes/knowledge/hunter-pilot-v0.41/coverage-ledger-input.json",
    );
    const original = readFileSync(file, "utf8");
    if (mode === "missing") rmSync(file);
    else
      writeFileSync(
        file,
        mode === "malformed"
          ? '{"private":"PRIVATE-COVERAGE-SENTINEL"}'
          : mode === "stale"
            ? original.replace('"1.0.0"', '"0.9.0"')
            : original.replace("issue #298", "issue #999"),
      );
    writeFileSync(join(dir, "codefit.db"), "PRIVATE-LEARNER-SENTINEL");
    const before = readdirSync(dir);
    const s = new CurriculumSession({
      ...runtimeLocation(resolve("java")),
      cwd: dir,
      classpath: [join(dir, "classes"), resolve("java/engine/lib/*")].join(
        delimiter,
      ),
    });
    try {
      assert.deepEqual(await s.read(), { ok: false, code: "INVALID_CATALOG" });
      assert.deepEqual(readdirSync(dir), before);
      assert.equal(
        readFileSync(join(dir, "codefit.db"), "utf8"),
        "PRIVATE-LEARNER-SENTINEL",
      );
    } finally {
      await s.stop();
      rmSync(dir, { recursive: true, force: true });
    }
  });

for (const [name, output] of [
  ["oversized", "'x'.repeat(32770)"],
  ["invalid JSON", "'{bad}\\n'"],
  [
    "duplicate keys",
    "JSON.stringify({v:1,id:req.id,ok:false,code:'INVALID_CATALOG'}).replace('\"v\":1','\"v\":1,\"v\":1')+'\\n'",
  ],
  [
    "wrong identity",
    "JSON.stringify({v:1,id:'00000000-0000-4000-8000-000000000000',ok:false,code:'INVALID_CATALOG'})+'\\n'",
  ],
  [
    "wrong version",
    "JSON.stringify({v:2,id:req.id,ok:false,code:'INVALID_CATALOG'})+'\\n'",
  ],
  [
    "private extra field",
    "JSON.stringify({v:1,id:req.id,ok:false,code:'INVALID_CATALOG',private:'secret'})+'\\n'",
  ],
  [
    "extra frame",
    "JSON.stringify({v:1,id:req.id,ok:false,code:'INVALID_CATALOG'})+'\\n{}\\n'",
  ],
  ["partial EOF", "'{\"v\":1'"],
  ["invalid UTF8", "Buffer.from([255,10])"],
] as const)
  test(`${name} response is rejected`, async () => {
    await fixture(`${onRead} process.stdout.write(${output}); });`, async (s) =>
      assert.deepEqual(await s.read(), { ok: false, code: "PROTOCOL_ERROR" }),
    );
  });

test("spoofed approvals, changed public metadata and unsupported actions fail schema/hash validation", async () => {
  const valid = await real();
  assert.ok(valid.ok);
  if (!valid.ok) return;
  for (const mutate of [
    (r: any) => (r.result.preview.independentApprovals = 1),
    (r: any) => (r.result.preview.skills[0].title = "Altered title"),
    (r: any) => (r.result.preview.graderAvailable = true),
    (r: any) => (r.result.preview.skills[0].stages[0].prompt = "protected"),
    (r: any) => r.result.preview.blockers.pop(),
    (r: any) => (r.result.preview.skills[0].id = "java.general.fake.v1"),
    (r: any) => delete r.result.coverage,
    (r: any) => (r.result.coverage.localReviewOnly = false),
    (r: any) => (r.result.coverage.productionImportSupported = true),
    (r: any) => (r.result.coverage.privateNotes = "PRIVATE-COVERAGE-SENTINEL"),
    (r: any) => (r.result.coverage.ledgerVersion = "0.9.0"),
    (r: any) => (r.result.coverage.ledgerSha256 = "0".repeat(64)),
    (r: any) => (r.result.coverage.provenance.source = "Unattributed source"),
    (r: any) => (r.result.coverage.books[0].preliminaryMapped.count = 1),
    (r: any) => (r.result.coverage.books[0].inventory.count += 1),
    (r: any) => (r.result.coverage.books[0].title = "Altered book"),
    (r: any) => (r.result.coverage.reportedClaims.revisionEvents.additiveToMissionBlueprintSlots = true),
  ]) {
    const bad = structuredClone(valid);
    mutate(bad);
    await fixture(
      `${onRead} process.stdout.write(JSON.stringify({v:1,id:req.id,...${JSON.stringify(bad)}})+'\\n'); });`,
      async (s) =>
        assert.deepEqual(await s.read(), { ok: false, code: "PROTOCOL_ERROR" }),
    );
  }
  assert.ok(CurriculumResultSchema.safeParse(valid).success);
});

test("timeout, cancellation and crash are bounded; stubborn children are killed", async () => {
  await fixture(
    "process.on('SIGTERM',()=>{}); setInterval(()=>{},1000);",
    async (s) => {
      assert.deepEqual(await s.read(), { ok: false, code: "TIMEOUT" });
      await s.stop();
    },
    200,
  );
  await fixture("setInterval(()=>{},1000);", async (s) => {
    const result = s.read();
    await s.stop();
    assert.deepEqual(await result, { ok: false, code: "CANCELLED" });
  });
  await fixture("process.exit(1);", async (s) =>
    assert.deepEqual(await s.read(), { ok: false, code: "UNAVAILABLE" }),
  );
  await fixture("process.stderr.write('x'.repeat(4097));", async (s) =>
    assert.deepEqual(await s.read(), { ok: false, code: "PROTOCOL_ERROR" }),
  );
});

test("release and caller authorization gates do not create a Java process", async () => {
  let creates = 0;
  const factory = () => {
    creates++;
    throw Error();
  };
  assert.deepEqual(await new CurriculumOwner(false, factory).read(() => true), {
    ok: false,
    code: "RELEASE_DISABLED",
  });
  assert.deepEqual(await new CurriculumOwner(true, factory).read(() => false), {
    ok: false,
    code: "UNAVAILABLE",
  });
  assert.equal(creates, 0);
});

test("concurrent atlas reads share one reader and one immutable response", async () => {
  let creates = 0;
  let reads = 0;
  let stops = 0;
  let finish!: (result: CurriculumResult) => void;
  const owner = new CurriculumOwner(true, (): CurriculumReader => {
    creates++;
    return {
      read: () => {
        reads++;
        return new Promise((resolve) => {
          finish = resolve;
        });
      },
      stop: async () => {
        stops++;
      },
    };
  });
  const first = owner.read(() => true);
  const second = owner.read(() => true);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(creates, 1);
  assert.equal(reads, 1);
  finish({ ok: false, code: "INVALID_CATALOG" });
  assert.deepEqual(await Promise.all([first, second]), [
    { ok: false, code: "INVALID_CATALOG" },
    { ok: false, code: "INVALID_CATALOG" },
  ]);
  assert.equal(stops, 1);
  await owner.shutdown();
});

test("cancel/reload fences delayed readers and allows a clean new generation", async () => {
  const resolvers: ((r: CurriculumResult) => void)[] = [];
  const owner = new CurriculumOwner(
    true,
    (): CurriculumReader => ({
      read: () => new Promise((r) => resolvers.push(r)),
      stop: async () => {},
    }),
  );
  const a = owner.read(() => true);
  await new Promise((r) => setImmediate(r));
  await owner.cancel();
  const b = owner.read(() => true);
  await new Promise((r) => setImmediate(r));
  resolvers[0]({ ok: false, code: "INVALID_CATALOG" });
  assert.deepEqual(await a, { ok: false, code: "CANCELLED" });
  resolvers[1]({ ok: false, code: "INVALID_CATALOG" });
  assert.deepEqual(await b, { ok: false, code: "INVALID_CATALOG" });
  await owner.shutdown();
  assert.deepEqual(await owner.read(() => true), {
    ok: false,
    code: "UNAVAILABLE",
  });
});
