import { strictJson } from "./strict-json";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";
import { join, delimiter } from "node:path";
import { existsSync } from "node:fs";
import {
  MAX_FRAME_BYTES,
  MAX_REQUEST_BYTES,
  ReadySchema,
  RequestSchema,
  WireResponseSchema,
  type ErrorCode,
  type Result,
  type Command,
} from "../src/contract";

export interface RuntimeLocation {
  java: string;
  classpath: string;
  cwd: string;
}
export function runtimeLocation(root: string): RuntimeLocation {
  const java = join(
    root,
    "runtime",
    "bin",
    process.platform === "win32" ? "java.exe" : "java",
  );
  const engine = join(root, "engine");
  if (
    !existsSync(java) ||
    !existsSync(
      join(
        engine,
        "classes",
        "com",
        "codefit",
        "desktop",
        "DesktopApplicationMain.class",
      ),
    )
  )
    throw new Error("START_FAILED");
  return {
    java,
    classpath: [join(engine, "classes"), join(engine, "lib", "*")].join(
      delimiter,
    ),
    cwd: root,
  };
}

/** One process, one in-flight request. Failed transports cannot be reused; application command identity survives in the journal. */
export class JavaSession {
  private child?: ChildProcessWithoutNullStreams;
  private buffer = Buffer.alloc(0);
  private logBytes = 0;
  private ready = false;
  private profileId?: string;
  private closed = false;
  private busy = false;
  private failure: ErrorCode = "UNAVAILABLE";
  private startResolve?: () => void;
  private startReject?: (error: Error) => void;
  private startTimer?: NodeJS.Timeout;
  private started?: Promise<void>;
  private pending?: {
    id: string;
    command: Command;
    resolve: (result: Result) => void;
    timer: NodeJS.Timeout;
  };
  private exitResolve?: () => void;
  private readonly exited = new Promise<void>((resolve) => {
    this.exitResolve = resolve;
  });
  private terminationTimers: NodeJS.Timeout[] = [];

  constructor(
    private readonly runtime: RuntimeLocation,
    private readonly profile: string,
    private readonly deadlineMs = 15_000,
  ) {}
  get pid(): number | undefined {
    return this.child?.pid;
  }

  private start(): Promise<void> {
    if (this.started) return this.started;
    this.started = new Promise<void>((resolve, reject) => {
      this.startResolve = resolve;
      this.startReject = reject;
      const env = { ...process.env };
      for (const key of [
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "CLASSPATH",
      ])
        delete env[key];
      const child = spawn(
        this.runtime.java,
        [
          "-Xms16m",
          "-Xmx128m",
          "-Djava.awt.headless=true",
          "-cp",
          this.runtime.classpath,
          "com.codefit.desktop.DesktopApplicationMain",
          "--parent",
          String(process.pid),
          "--experimental-profile",
          this.profile,
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
      this.startTimer = setTimeout(() => {
        void this.stop("TIMEOUT");
      }, this.deadlineMs);
      child.stdout.on("data", (chunk: Buffer) => this.consume(chunk));
      // Drain logs without exposing paths/content to the renderer or allowing unbounded retention.
      child.stderr.on("data", (chunk: Buffer) => {
        this.logBytes += chunk.length;
        if (this.logBytes > 16_384) void this.stop("PROTOCOL_ERROR");
      });
      child.stdin.on("error", () => {
        void this.stop("UNAVAILABLE");
      });
      child.on("error", () => {
        void this.stop("START_FAILED");
      });
      child.on("close", () => {
        this.fail(
          this.closed
            ? this.failure
            : this.ready
              ? "UNAVAILABLE"
              : "START_FAILED",
        );
        this.terminationTimers.forEach(clearTimeout);
        this.child = undefined;
        this.exitResolve?.();
      });
    });
    return this.started;
  }

  private consume(chunk: Buffer): void {
    if (this.closed) return;
    // A read chunk may contain several frames. Bound each unfinished frame before allocating it.
    let offset = 0;
    while (offset < chunk.length) {
      const newline = chunk.indexOf(10, offset);
      const end = newline < 0 ? chunk.length : newline;
      if (this.buffer.length + end - offset > MAX_FRAME_BYTES) {
        void this.stop("PROTOCOL_ERROR");
        return;
      }
      this.buffer = Buffer.concat([this.buffer, chunk.subarray(offset, end)]);
      if (newline < 0) return;
      const frame = this.buffer;
      this.buffer = Buffer.alloc(0);
      try {
        const message: unknown = strictJson(
          new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(
            frame,
          ),
        );
        if (!this.ready) {
          const startup = ReadySchema.parse(message);
          if (startup.type === "startup-error") {
            void this.stop(startup.code);
            return;
          }
          this.profileId = startup.profileId;
          this.ready = true;
          clearTimeout(this.startTimer);
          this.startResolve?.();
        } else {
          const reply = WireResponseSchema.parse(message);
          if (!this.pending || reply.id !== this.pending.id)
            throw new Error("uncorrelated");
          const pending = this.pending;
          if (reply.ok) {
            const expected = {
              "state.read": "state",
              "decks.create": "receipt",
              "commands.status": "command-status",
            }[pending.command.method];
            if (reply.result.kind !== expected) throw new Error("wrong result");
            if (
              reply.result.kind === "state" &&
              reply.result.profileId !== this.profileId
            )
              throw new Error("wrong profile");
            if (reply.result.kind !== "state") {
              if (
                !("commandId" in pending.command) ||
                reply.result.commandId !== pending.command.commandId
              )
                throw new Error("wrong command");
              if (
                reply.result.kind === "command-status" &&
                reply.result.receipt &&
                reply.result.receipt.commandId !== reply.result.commandId
              )
                throw new Error("wrong receipt");
            }
          }
          this.pending = undefined;
          clearTimeout(pending.timer);
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

  async request(command: Command): Promise<Result> {
    if (this.closed) return { ok: false, code: this.failure };
    if (this.busy) return { ok: false, code: "BUSY" };
    this.busy = true;
    try {
      await this.start();
      if (this.closed) return { ok: false, code: this.failure };
      if ("profileId" in command && command.profileId !== this.profileId)
        return { ok: false, code: "PROFILE_MISMATCH" };
      const request = RequestSchema.parse({
        ...command,
        v: 2,
        id: randomUUID(),
      });
      const frame = JSON.stringify(request) + "\n";
      if (Buffer.byteLength(frame) > MAX_REQUEST_BYTES + 1)
        return { ok: false, code: "INVALID_INPUT" };
      return await new Promise<Result>((resolve) => {
        this.pending = {
          id: request.id,
          command,
          resolve,
          timer: setTimeout(() => {
            void this.stop("TIMEOUT");
          }, this.deadlineMs),
        };
        this.child!.stdin.write(frame);
      });
    } catch {
      return { ok: false, code: this.failure };
    } finally {
      this.busy = false;
    }
  }

  private fail(code: ErrorCode): void {
    this.closed = true;
    this.failure = code;
    clearTimeout(this.startTimer);
    this.startReject?.(new Error(code));
    if (this.pending) {
      clearTimeout(this.pending.timer);
      this.pending.resolve({ ok: false, code });
      this.pending = undefined;
    }
  }

  async stop(code: ErrorCode = "CANCELLED"): Promise<void> {
    if (!this.closed) {
      this.fail(code);
      if (this.child) {
        const child = this.child;
        child.stdin.end(); // EOF gives Java the opportunity to close its own database before releasing ownership.
        this.terminationTimers.push(
          setTimeout(() => child.kill("SIGTERM"), 500),
        );
        this.terminationTimers.push(
          setTimeout(() => child.kill("SIGKILL"), 2_000),
        );
      } else this.exitResolve?.();
    }
    await this.exited;
  }
}
