import { test } from "node:test";
import assert from "node:assert/strict";
import {
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
  symlinkSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { DesktopApplication } from "../electron/desktop-application";
import { OperationJournal } from "../electron/operation-journal";
import { EngineOwner } from "../electron/engine-owner";
import { JavaSession, runtimeLocation } from "../electron/java-session";
import { strictJson } from "../electron/strict-json";
import { type Command, type Result, type Intent } from "../src/contract";
const profileId = "8f12a1b0-56c1-4c3e-a111-3495bbce64a0";
const commandId = "52a766f0-afc2-48e6-8d60-111c5f8cf814";
const sampleState: Result = {
  ok: true,
  result: {
    kind: "state",
    source: "experimental-profile",
    profileId,
    readOnly: true,
    progression: { status: "unsupported" },
    decks: [],
    nextAfterId: null,
  },
};
const allowed = () => true;
const intent: Intent = {
  v: 1,
  profileId,
  commandId,
  input: { name: "Recovery deck", description: "Local only" },
};
function temporary() {
  return mkdtempSync(join(tmpdir(), "codefit-application-test-"));
}

test("real committed response lost before acknowledgement survives main restart without duplicate effect", async () => {
  const directory = temporary(),
    profile = join(directory, "profile"),
    journalDirectory = join(directory, "journal");
  const journal = new OperationJournal(journalDirectory);
  const sent: Command[] = [];
  let drop = true;
  let owner = new EngineOwner(() => {
    const session = new JavaSession(runtimeLocation(resolve("java")), profile);
    return {
      stop: () => session.stop(),
      request: async (command: Command): Promise<Result> => {
        sent.push(command);
        if (command.method === "decks.create")
          assert.equal(
            journal.read()?.commandId,
            command.commandId,
            "intent durable before dispatch",
          );
        const result = await session.request(command);
        if (command.method === "decks.create" && drop) {
          drop = false;
          await session.stop();
          return { ok: false, code: "UNAVAILABLE" };
        }
        return result;
      },
    };
  });
  try {
    const original = new DesktopApplication(owner, journal);
    const interrupted = await original.create(intent.input, allowed);
    if (!interrupted.ok || !interrupted.operation)
      throw new Error("missing durable operation");
    assert.equal(interrupted.operation.receipt, null);
    assert.equal(interrupted.operation.error, "UNAVAILABLE");
    const saved = journal.read()!;
    assert.equal(saved.commandId, interrupted.operation.commandId);
    await owner.shutdown();
    owner = new EngineOwner(
      () => new JavaSession(runtimeLocation(resolve("java")), profile),
    );
    const restarted = new DesktopApplication(
      owner,
      new OperationJournal(journalDirectory),
    );
    const before = readFileSync(join(journalDirectory, "pending-deck.json"));
    const checked = await restarted.pending(allowed);
    if (!checked.ok || !checked.operation?.receipt)
      throw new Error("missing receipt after restart");
    assert.equal(checked.operation.receipt.status, "created");
    assert.equal(checked.operation.commandId, saved.commandId);
    assert.deepEqual(
      readFileSync(join(journalDirectory, "pending-deck.json")),
      before,
    );
    assert.deepEqual(
      await restarted.create({ name: "Another", description: "" }, allowed),
      { ok: false, code: "RECOVERY_REQUIRED" },
    );
    assert.deepEqual(await restarted.recover(allowed), checked);
    const page = await restarted.read(0, allowed);
    if (!page.ok || page.result.kind !== "state")
      throw new Error("missing state");
    const last = await restarted.read(page.result.nextAfterId, allowed);
    if (!last.ok || last.result.kind !== "state")
      throw new Error("missing last page");
    assert.equal(last.result.decks.length, 1);
    assert.equal(last.result.decks[0].name, "Recovery deck");
    assert.equal(sent.filter((c) => c.method === "decks.create").length, 1);
    assert.deepEqual(await restarted.acknowledge(saved.commandId, allowed), {
      ok: true,
    });
    assert.equal(journal.read(), null);
  } finally {
    await owner.shutdown();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("unknown completion retries exact durable identity only after explicit recovery", async () => {
  const directory = temporary(),
    journal = new OperationJournal(directory);
  let attempts = 0;
  const calls: Command[] = [];
  const owner = new EngineOwner(() => ({
    stop: async () => {},
    request: async (command): Promise<Result> => {
      calls.push(command);
      if (command.method === "state.read") return sampleState;
      if (command.method === "commands.status")
        return {
          ok: true,
          result: {
            kind: "command-status",
            commandId: command.commandId,
            receipt: null,
          },
        };
      assert.deepEqual(journal.read()?.input, intent.input);
      if (++attempts === 1) return { ok: false, code: "TIMEOUT" };
      return {
        ok: true,
        result: {
          kind: "receipt",
          commandId: command.commandId,
          status: "created",
          deck: { id: 9, name: command.name, description: command.description },
        },
      };
    },
  }));
  const app = new DesktopApplication(owner, journal);
  try {
    const unknown = await app.create(intent.input, allowed);
    if (!unknown.ok || !unknown.operation) throw new Error("missing operation");
    assert.equal(unknown.operation.error, "TIMEOUT");
    const id = unknown.operation.commandId;
    const checked = await app.pending(allowed);
    assert.equal(checked.ok && checked.operation?.receipt, null);
    assert.equal(attempts, 1);
    assert.deepEqual(await app.acknowledge(id, allowed), {
      ok: false,
      code: "RECOVERY_REQUIRED",
    });
    assert.equal(journal.read()?.commandId, id);
    const recovered = await app.recover(allowed);
    assert.equal(
      recovered.ok && recovered.operation?.receipt?.status,
      "created",
    );
    const writes = calls.filter((c) => c.method === "decks.create");
    assert.equal(writes.length, 2);
    assert.deepEqual(writes[0], writes[1]);
  } finally {
    await owner.shutdown();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("profile replacement blocks saved-command replay and acknowledgement", async () => {
  const directory = temporary(),
    journal = new OperationJournal(directory);
  journal.write(intent);
  let writes = 0;
  const owner = new EngineOwner(() => ({
    stop: async () => {},
    request: async (command): Promise<Result> => {
      if (command.method === "decks.create") writes++;
      return { ok: false, code: "PROFILE_MISMATCH" };
    },
  }));
  const app = new DesktopApplication(owner, journal);
  try {
    const result = await app.recover(allowed);
    assert.equal(result.ok && result.operation?.error, "PROFILE_MISMATCH");
    assert.equal(writes, 0);
    assert.deepEqual(await app.acknowledge(commandId, allowed), {
      ok: false,
      code: "RECOVERY_REQUIRED",
    });
    assert.deepEqual(journal.read(), intent);
  } finally {
    await owner.shutdown();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("invalid, unauthorized and corrupt-journal requests cannot start Java", async () => {
  const directory = temporary(),
    journal = new OperationJournal(directory);
  let starts = 0;
  const owner = new EngineOwner(() => {
    starts++;
    throw new Error("must not start");
  });
  const app = new DesktopApplication(owner, journal);
  try {
    for (const bad of [
      null,
      {},
      { name: "", description: "" },
      { name: " ", description: "" },
      { name: "a\n", description: "" },
      { name: "\ud800", description: "" },
      { name: "x".repeat(257), description: "" },
      { name: "valid", description: "", sql: "SELECT 1" },
    ])
      assert.deepEqual(await app.create(bad, allowed), {
        ok: false,
        code: "INVALID_INPUT",
      });
    assert.deepEqual(await app.create(intent.input, () => false), {
      ok: false,
      code: "UNAVAILABLE",
    });
    assert.deepEqual(await app.read(-1, allowed), {
      ok: false,
      code: "INVALID_INPUT",
    });
    writeFileSync(join(directory, "pending-deck.json"), "{malformed");
    assert.deepEqual(await app.pending(allowed), {
      ok: false,
      code: "JOURNAL_INVALID",
    });
    assert.deepEqual(await app.create(intent.input, allowed), {
      ok: false,
      code: "JOURNAL_INVALID",
    });
    assert.equal(starts, 0);
  } finally {
    await owner.shutdown();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("concurrent create calls cannot mint two intents while first transport is pending", async () => {
  const directory = temporary(),
    journal = new OperationJournal(directory);
  let release!: (value: Result) => void;
  const pending = new Promise<Result>((r) => {
    release = r;
  });
  const owner = new EngineOwner(() => ({
    stop: async () => {},
    request: async () => pending,
  }));
  const app = new DesktopApplication(owner, journal);
  try {
    const first = app.create(intent.input, allowed);
    assert.deepEqual(await app.create(intent.input, allowed), {
      ok: false,
      code: "BUSY",
    });
    release({ ok: false, code: "CANCELLED" });
    assert.deepEqual(await first, { ok: false, code: "CANCELLED" });
    assert.equal(journal.read(), null);
  } finally {
    await owner.shutdown();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("journal bounds, links and duplicate keys fail closed without replacing an intent", () => {
  const directory = temporary(),
    journal = new OperationJournal(directory);
  try {
    journal.write(intent);
    assert.deepEqual(journal.read(), intent);
    assert.throws(
      () => journal.write({ ...intent, commandId: profileId }),
      /RECOVERY_REQUIRED/,
    );
    writeFileSync(join(directory, "pending-deck.json"), "x".repeat(16385));
    assert.throws(() => journal.read(), /JOURNAL_INVALID/);
    rmSync(join(directory, "pending-deck.json"));
    symlinkSync(
      join(directory, "missing"),
      join(directory, "pending-deck.json"),
    );
    assert.throws(() => journal.read(), /JOURNAL_INVALID/);
    assert.throws(() => strictJson('{"v":1,"v":1}'), /DUPLICATE_KEY/);
    assert.throws(
      () => strictJson('{"name":1,"na\\u006de":2}'),
      /DUPLICATE_KEY/,
    );
    assert.deepEqual(strictJson('{"a":[{"b":"escaped\\\" {"}]}'), {
      a: [{ b: 'escaped" {' }],
    });
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
