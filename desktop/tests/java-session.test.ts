import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { JavaSession, runtimeLocation } from "../electron/java-session";

// Malicious/failed pipe fixtures supplement (never replace) the real Java workflow below.
async function fixture(
  source: string,
  check: (session: JavaSession) => Promise<void>,
  deadline = 1500,
) {
  const directory = mkdtempSync(join(tmpdir(), "codefit-pipe-test-"));
  const executable = join(directory, "fixture");
  writeFileSync(executable, `#!${process.execPath}\n${source}`, {
    mode: 0o700,
  });
  const session = new JavaSession(
    { java: executable, classpath: "unused", cwd: directory },
    deadline,
  );
  try {
    await check(session);
  } finally {
    await session.stop();
    rmSync(directory, { recursive: true, force: true });
  }
}
const ready = `process.stdout.write('{"v":1,"type":"ready"}\\n');`;
const onRequest = `process.stdin.on('data', data => { const request = JSON.parse(data.toString());`;
const result = `{v:1,id:request.id,ok:true,result:{source:'isolated-seed',readOnly:true,decks:[]}}`;
const closeOnEof = `process.stdin.on('end', () => process.exit(0));`;

test("real bundled Java process returns service content, repeats reads and exits on cancellation", async () => {
  const session = new JavaSession(runtimeLocation(resolve("java")));
  try {
    const first = await session.listDecks();
    if (!first.ok) throw new Error(first.code);
    assert.equal(first.result.decks.length, 8);
    assert.match(first.result.decks[0].name, /Core Java & OOP/);
    assert.deepEqual(await session.listDecks(), first);
    const pid = session.pid!;
    await session.stop();
    assert.throws(() => process.kill(pid, 0), { code: "ESRCH" });
    assert.deepEqual(await session.listDecks(), {
      ok: false,
      code: "CANCELLED",
    });
  } finally {
    await session.stop();
  }
});

test("missing bundled runtime fails without PATH fallback", async () => {
  assert.throws(
    () => runtimeLocation("/missing-codefit-gate0"),
    /START_FAILED/,
  );
  const session = new JavaSession({
    java: "/missing-codefit-gate0/java",
    classpath: "",
    cwd: process.cwd(),
  });
  assert.deepEqual(await session.listDecks(), {
    ok: false,
    code: "START_FAILED",
  });
  await session.stop();
});

test("one in-flight read, timeout, and process termination are bounded", async () => {
  await fixture(
    `${ready} process.stdin.resume(); ${closeOnEof}`,
    async (session) => {
      const first = session.listDecks();
      assert.deepEqual(await session.listDecks(), { ok: false, code: "BUSY" });
      assert.deepEqual(await first, { ok: false, code: "TIMEOUT" });
    },
    200,
  );
});

test("startup timeout and cancellation also work before ready", async () => {
  await fixture(
    `process.stdin.resume(); ${closeOnEof}`,
    async (session) => {
      assert.deepEqual(await session.listDecks(), {
        ok: false,
        code: "TIMEOUT",
      });
    },
    200,
  );
  await fixture(`process.stdin.resume(); ${closeOnEof}`, async (session) => {
    const read = session.listDecks();
    await session.stop();
    assert.deepEqual(await read, { ok: false, code: "CANCELLED" });
  });
});

for (const [name, source] of Object.entries({
  "malformed JSON": `process.stdout.write('not json\\n'); process.stdin.resume();`,
  "oversized frame": `process.stdout.write('x'.repeat(65537)); process.stdin.resume();`,
  "invalid UTF-8": `process.stdout.write(Buffer.from([255,10])); process.stdin.resume();`,
  "stderr overflow": `process.stderr.write('x'.repeat(16385)); process.stdin.resume();`,
  "wrong version": `process.stdout.write('{"v":2,"type":"ready"}\\n'); process.stdin.resume();`,
  "uncorrelated reply": `${ready}${onRequest} process.stdout.write(JSON.stringify({...${result},id:'52a766f0-afc2-48e6-8d60-111c5f8cf814'})+'\\n'); });`,
  "extra private field": `${ready}${onRequest} process.stdout.write(JSON.stringify({...${result},sourceCode:'private'})+'\\n'); });`,
}))
  test(`fails closed on ${name}`, async () => {
    await fixture(source + closeOnEof, async (session) => {
      assert.deepEqual(await session.listDecks(), {
        ok: false,
        code: "PROTOCOL_ERROR",
      });
    });
  });

test("unexpected crash and partial EOF never return success", async () => {
  await fixture(
    `${ready}${onRequest} process.stdout.write('{'); process.exit(1); });`,
    async (session) => {
      assert.deepEqual(await session.listDecks(), {
        ok: false,
        code: "UNAVAILABLE",
      });
    },
  );
});

test("ignoring EOF and SIGTERM escalates to process kill", async () => {
  await fixture(
    `${ready} process.stdin.resume(); process.on('SIGTERM',()=>{}); setInterval(()=>{},1000);`,
    async (session) => {
      assert.deepEqual(await session.listDecks(), {
        ok: false,
        code: "TIMEOUT",
      });
      const pid = session.pid!;
      await session.stop();
      assert.throws(() => process.kill(pid, 0), { code: "ESRCH" });
    },
    200,
  );
});

test("inherited Java injection variables are removed", async () => {
  const keys = [
    "JAVA_TOOL_OPTIONS",
    "_JAVA_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "CLASSPATH",
  ];
  const before = keys.map((key) => process.env[key]);
  keys.forEach((key) => {
    process.env[key] = "untrusted";
  });
  try {
    await fixture(
      `${ready}${onRequest} const keys=${JSON.stringify(keys)}; if(keys.some(k=>process.env[k])) process.exit(1); process.stdout.write(JSON.stringify(${result})+'\\n'); });${closeOnEof}`,
      async (session) => {
        assert.equal((await session.listDecks()).ok, true);
      },
    );
  } finally {
    keys.forEach((key, i) => {
      if (before[i] === undefined) delete process.env[key];
      else process.env[key] = before[i];
    });
  }
});
