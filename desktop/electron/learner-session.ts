import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { strictJson } from "./strict-json";
import type { RuntimeLocation } from "./java-session";
import {
  LearnerReadySchema,
  LearnerRequestSchema,
  LearnerWireSchema,
  type LearnerError,
  type LearnerResult,
} from "../src/learner-contract";

/** One read-only snapshot session. Concurrent reads share a single bounded request. */
export class LearnerSession {
  private child?: ChildProcessWithoutNullStreams;
  private closed = false;
  private failure: LearnerError = "UNAVAILABLE";
  private buffer = Buffer.alloc(0);
  private logs = 0;
  private started?: Promise<void>;
  private startResolve?: () => void;
  private startReject?: () => void;
  private timer?: NodeJS.Timeout;
  private ready = false;
  private profileId?: string;
  private readonly sessionId = randomUUID();
  private reading?: Promise<LearnerResult>;
  private pending?: { id: string; resolve: (result: LearnerResult) => void };
  private exitResolve!: () => void;
  private readonly exited = new Promise<void>((resolve) => {
    this.exitResolve = resolve;
  });
  private terminationTimers: NodeJS.Timeout[] = [];

  constructor(
    private readonly runtime: RuntimeLocation,
    private readonly source: string,
    private readonly backups: string,
    private readonly deadline = 20_000,
  ) {}

  private start(): Promise<void> {
    if (this.started) return this.started;
    this.started = new Promise<void>((resolve, reject) => {
      this.startResolve = resolve;
      this.startReject = () => reject(new Error("START_FAILED"));
      const env = { ...process.env };
      for (const name of [
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "CLASSPATH",
      ])
        delete env[name];
      const child = spawn(
        this.runtime.java,
        [
          "-Xms16m",
          "-Xmx128m",
          "-Djava.awt.headless=true",
          "-cp",
          this.runtime.classpath,
          "com.codefit.desktop.LearnerProfileMain",
          "--parent",
          String(process.pid),
          "--source",
          this.source,
          "--snapshots",
          this.backups,
          "--session",
          this.sessionId,
        ],
        {
          cwd: this.runtime.cwd,
          stdio: ["pipe", "pipe", "pipe"],
          windowsHide: true,
          shell: false,
          env,
        },
      );
      this.child = child;
      this.timer = setTimeout(() => {
        void this.stop("TIMEOUT");
      }, this.deadline);
      child.stdout.on("data", (chunk: Buffer) => this.consume(chunk));
      child.stderr.on("data", (chunk: Buffer) => {
        this.logs += chunk.length;
        if (this.logs > 4096) void this.stop("PROTOCOL_ERROR");
      });
      child.stdin.on("error", () => {
        void this.stop("UNAVAILABLE");
      });
      child.on("error", () => {
        void this.stop("START_FAILED");
      });
      child.on("close", () => {
        this.fail(this.closed ? this.failure : "UNAVAILABLE");
        this.terminationTimers.forEach(clearTimeout);
        this.child = undefined;
        this.exitResolve();
      });
    });
    return this.started;
  }

  private consume(chunk: Buffer): void {
    if (this.closed) return;
    let offset = 0;
    while (offset < chunk.length) {
      const newline = chunk.indexOf(10, offset);
      const end = newline < 0 ? chunk.length : newline;
      if (this.buffer.length + end - offset > 8192) {
        void this.stop("PROTOCOL_ERROR");
        return;
      }
      this.buffer = Buffer.concat([this.buffer, chunk.subarray(offset, end)]);
      if (newline < 0) return;
      const frame = this.buffer;
      this.buffer = Buffer.alloc(0);
      try {
        const message = strictJson(
          new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(
            frame,
          ),
        );
        if (!this.ready) {
          const ready = LearnerReadySchema.parse(message);
          if (ready.type === "startup-error") {
            void this.stop(ready.code);
            return;
          }
          if (ready.sessionId !== this.sessionId) throw new Error("session");
          this.profileId = ready.profileId;
          this.ready = true;
          clearTimeout(this.timer);
          this.startResolve?.();
        } else {
          const reply = LearnerWireSchema.parse(message);
          if (!this.pending || reply.id !== this.pending.id)
            throw new Error("correlation");
          if (
            reply.ok &&
            (reply.result.sessionId !== this.sessionId ||
              reply.result.profileId !== this.profileId)
          )
            throw new Error("identity");
          clearTimeout(this.timer);
          const pending = this.pending;
          this.pending = undefined;
          pending.resolve(
            reply.ok
              ? { ok: true, result: reply.result }
              : { ok: false, code: reply.code },
          );
        }
      } catch {
        void this.stop("PROTOCOL_ERROR");
        return;
      }
      offset = end + 1;
    }
  }

  read(): Promise<LearnerResult> {
    if (this.closed) return Promise.resolve({ ok: false, code: this.failure });
    if (this.reading) return this.reading;
    this.reading = this.readOne().finally(() => {
      this.reading = undefined;
    });
    return this.reading;
  }
  private async readOne(): Promise<LearnerResult> {
    try {
      await this.start();
      if (this.closed) return { ok: false, code: this.failure };
      const request = LearnerRequestSchema.parse({
        v: 3,
        id: randomUUID(),
        method: "learner.overview.read",
        sessionId: this.sessionId,
        profileId: this.profileId,
      });
      return await new Promise<LearnerResult>((resolve) => {
        this.pending = { id: request.id, resolve };
        this.timer = setTimeout(() => {
          void this.stop("TIMEOUT");
        }, this.deadline);
        this.child!.stdin.write(JSON.stringify(request) + "\n");
      });
    } catch {
      return { ok: false, code: this.failure };
    }
  }
  private fail(code: LearnerError): void {
    this.closed = true;
    this.failure = code;
    clearTimeout(this.timer);
    this.startReject?.();
    this.pending?.resolve({ ok: false, code });
    this.pending = undefined;
  }
  async stop(code: LearnerError = "CANCELLED"): Promise<void> {
    if (!this.closed) {
      this.fail(code);
      const child = this.child;
      if (child) {
        child.stdin.end();
        this.terminationTimers.push(
          setTimeout(() => child.kill("SIGTERM"), 100),
          setTimeout(() => child.kill("SIGKILL"), 1000),
        );
      } else this.exitResolve();
    }
    await this.exited;
  }
}

export interface LearnerReader {
  read(): Promise<LearnerResult>;
  stop(): Promise<void>;
}
/** Generation-bound selection and teardown: a replaced profile can never publish into a new session. */
export class LearnerOwner {
  private current?: LearnerReader;
  private generation = 0;
  private closing: Promise<void> = Promise.resolve();
  private closed = false;
  constructor(
    private readonly runtime: () => RuntimeLocation,
    private readonly backups: string,
    private readonly create: (source: string) => LearnerReader = (source) =>
      new LearnerSession(runtime(), source, backups),
  ) {}

  async connect(
    source: string,
    authorized: () => boolean,
  ): Promise<LearnerResult> {
    if (this.closed || !authorized()) return { ok: false, code: "UNAVAILABLE" };
    const generation = ++this.generation;
    const old = this.current;
    this.current = undefined;
    this.closing = Promise.all([this.closing, old?.stop()]).then(() => {});
    await this.closing;
    if (this.closed || generation !== this.generation || !authorized())
      return { ok: false, code: "CANCELLED" };
    try {
      this.current = this.create(source);
    } catch {
      return { ok: false, code: "START_FAILED" };
    }
    return this.read(authorized);
  }
  async read(authorized: () => boolean): Promise<LearnerResult> {
    const selected = this.current;
    const generation = this.generation;
    if (this.closed || !selected || !authorized())
      return { ok: false, code: "UNAVAILABLE" };
    const result = await selected.read();
    if (
      this.closed ||
      generation !== this.generation ||
      selected !== this.current ||
      !authorized()
    )
      return { ok: false, code: "CANCELLED" };
    if (!result.ok) await this.disconnect();
    return result;
  }
  async disconnect(): Promise<void> {
    ++this.generation;
    const selected = this.current;
    this.current = undefined;
    this.closing = Promise.all([this.closing, selected?.stop()]).then(() => {});
    await this.closing;
  }
  async shutdown(): Promise<void> {
    this.closed = true;
    await this.disconnect();
  }
}
