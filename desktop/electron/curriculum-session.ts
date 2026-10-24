import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID, createHash } from "node:crypto";
import type { RuntimeLocation } from "./java-session";
import { strictJson } from "./strict-json";
import {
  CURRICULUM_MAX_BYTES,
  PUBLIC_PREVIEW_SHA,
  CurriculumRequestSchema,
  CurriculumWireSchema,
  type CurriculumResult,
  type CurriculumError,
} from "../src/curriculum-contract";

export interface CurriculumReader {
  read(): Promise<CurriculumResult>;
  stop(code?: CurriculumError): Promise<void>;
}
/** One bounded request, response and clean process exit. Never owns a profile or cached learner state. */
export class CurriculumSession implements CurriculumReader {
  private child?: ChildProcessWithoutNullStreams;
  private reading?: Promise<CurriculumResult>;
  private resolve?: (result: CurriculumResult) => void;
  private timer?: NodeJS.Timeout;
  private killTimers: NodeJS.Timeout[] = [];
  private failure?: CurriculumError;
  private exitResolve!: () => void;
  private exited = new Promise<void>((r) => {
    this.exitResolve = r;
  });
  constructor(
    private readonly runtime: RuntimeLocation,
    private readonly deadline = 12_000,
  ) {}
  read(): Promise<CurriculumResult> {
    if (this.failure) return Promise.resolve({ ok: false, code: this.failure });
    if (this.reading) return this.reading;
    this.reading = new Promise((resolve) => {
      this.resolve = resolve;
      const request = CurriculumRequestSchema.parse({
        v: 1,
        id: randomUUID(),
        method: "curriculum.preview.read",
      });
      const env = { ...process.env };
      for (const key of [
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "CLASSPATH",
      ])
        delete env[key];
      let child: ChildProcessWithoutNullStreams;
      try {
        child = spawn(
          this.runtime.java,
          [
            "-Xms16m",
            "-Xmx64m",
            "-Djava.awt.headless=true",
            "-cp",
            this.runtime.classpath,
            "com.codefit.desktop.CurriculumPreviewMain",
            "--parent",
            String(process.pid),
          ],
          {
            cwd: this.runtime.cwd,
            stdio: ["pipe", "pipe", "pipe"],
            windowsHide: true,
            shell: false,
            env,
          },
        );
      } catch {
        this.failure = "START_FAILED";
        resolve({ ok: false, code: this.failure });
        this.exitResolve();
        return;
      }
      this.child = child;
      let bytes = Buffer.alloc(0),
        logs = 0;
      this.timer = setTimeout(() => {
        void this.stop("TIMEOUT");
      }, this.deadline);
      child.stdout.on("data", (chunk: Buffer) => {
        if (this.failure) return;
        if (bytes.length + chunk.length > CURRICULUM_MAX_BYTES + 1) {
          void this.stop("PROTOCOL_ERROR");
          return;
        }
        bytes = Buffer.concat([bytes, chunk]);
      });
      child.stderr.on("data", (chunk: Buffer) => {
        logs += chunk.length;
        if (logs > 4096) void this.stop("PROTOCOL_ERROR");
      });
      child.stdin.on("error", () => {
        void this.stop("UNAVAILABLE");
      });
      child.on("error", () => {
        void this.stop("START_FAILED");
      });
      child.on("close", (code) => {
        clearTimeout(this.timer);
        this.killTimers.forEach(clearTimeout);
        this.child = undefined;
        let result: CurriculumResult = {
          ok: false,
          code: this.failure ?? "UNAVAILABLE",
        };
        if (!this.failure && code === 0) {
          try {
            if (
              bytes.length === 0 ||
              bytes[bytes.length - 1] !== 10 ||
              bytes.subarray(0, -1).includes(10)
            )
              throw Error();
            const raw = strictJson(
              new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(
                bytes.subarray(0, -1),
              ),
            );
            const reply = CurriculumWireSchema.parse(raw);
            if (reply.id !== request.id) throw Error();
            if (
              reply.ok &&
              createHash("sha256")
                .update(JSON.stringify(reply.result.preview))
                .digest("hex") !== PUBLIC_PREVIEW_SHA
            )
              throw Error();
            result = reply.ok
              ? { ok: true, result: reply.result }
              : { ok: false, code: reply.code };
          } catch {
            result = { ok: false, code: "PROTOCOL_ERROR" };
          }
        }
        this.resolve?.(result);
        this.exitResolve();
      });
      child.stdin.end(JSON.stringify(request) + "\n");
    });
    return this.reading;
  }
  async stop(code: CurriculumError = "CANCELLED"): Promise<void> {
    if (!this.failure) {
      this.failure = code;
      clearTimeout(this.timer);
      this.resolve?.({ ok: false, code });
      if (this.child) {
        const child = this.child;
        child.stdin.end();
        this.killTimers.push(
          setTimeout(() => child.kill("SIGTERM"), 100),
          setTimeout(() => child.kill("SIGKILL"), 1000),
        );
      } else this.exitResolve();
    }
    await this.exited;
  }
}

/** Single coalesced read per owning renderer generation; cancellation never touches learner/deck owners. */
export class CurriculumOwner {
  private active?: CurriculumReader;
  private pending?: Promise<CurriculumResult>;
  private generation = 0;
  private closing: Promise<void> = Promise.resolve();
  private closed = false;
  constructor(
    private readonly enabled: boolean,
    private readonly create: () => CurriculumReader,
  ) {}
  async read(authorized: () => boolean): Promise<CurriculumResult> {
    if (this.closed || !authorized()) return { ok: false, code: "UNAVAILABLE" };
    if (!this.enabled) return { ok: false, code: "RELEASE_DISABLED" };
    const generation = this.generation;
    await this.closing;
    if (this.closed || generation !== this.generation || !authorized())
      return { ok: false, code: "CANCELLED" };
    if (!this.pending) {
      try {
        const reader = this.create();
        this.active = reader;
        this.pending = reader
          .read()
          .catch((): CurriculumResult => ({ ok: false, code: "UNAVAILABLE" }))
          .finally(async () => {
            await reader.stop();
            if (this.active === reader) {
              this.active = undefined;
              this.pending = undefined;
            }
          });
      } catch {
        return { ok: false, code: "START_FAILED" };
      }
    }
    const result = await this.pending;
    return !this.closed && generation === this.generation && authorized()
      ? result
      : { ok: false, code: "CANCELLED" };
  }
  async cancel(): Promise<void> {
    this.generation++;
    const active = this.active;
    this.active = undefined;
    this.pending = undefined;
    this.closing = Promise.all([this.closing, active?.stop()]).then(() => {});
    await this.closing;
  }
  async shutdown(): Promise<void> {
    this.closed = true;
    await this.cancel();
  }
}
