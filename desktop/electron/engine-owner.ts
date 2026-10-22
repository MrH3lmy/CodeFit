import { ResultSchema, type Result, type Command } from "../src/contract";

interface ApplicationSession {
  request(command: Command): Promise<Result>;
  stop(): Promise<void>;
}

/** Serializes session replacement; a stale request can only close its own session. */
export class EngineOwner {
  private current?: ApplicationSession;
  private closing?: Promise<void>;
  private shuttingDown = false;
  constructor(private readonly create: () => ApplicationSession) {}

  async execute(command: Command, authorized: () => boolean): Promise<Result> {
    if (this.shuttingDown || !authorized())
      return { ok: false, code: "UNAVAILABLE" };
    if (this.closing) await this.closing;
    // The window or owning frame may have gone away while cleanup was pending.
    if (this.shuttingDown || !authorized())
      return { ok: false, code: "UNAVAILABLE" };
    let selected: ApplicationSession | undefined;
    try {
      selected = this.current ??= this.create();
      const result = ResultSchema.parse(await selected.request(command));
      if (
        !result.ok &&
        [
          "START_FAILED",
          "UNAVAILABLE",
          "READ_FAILED",
          "COMMAND_FAILED",
          "PROTOCOL_ERROR",
          "TIMEOUT",
          "CANCELLED",
          "PROFILE_BUSY",
          "PROFILE_INVALID",
        ].includes(result.code)
      )
        await this.stop(selected);
      return result;
    } catch {
      if (selected) await this.stop(selected);
      return { ok: false, code: "START_FAILED" };
    }
  }

  async stop(expected = this.current): Promise<void> {
    if (expected !== this.current) return; // Old replies cannot kill a replacement process.
    if (this.closing) return this.closing;
    const previous = this.current;
    this.current = undefined;
    if (!previous) return;
    const cleanup = previous.stop();
    this.closing = cleanup;
    try {
      await cleanup;
    } finally {
      if (this.closing === cleanup) this.closing = undefined;
    }
  }

  async shutdown(): Promise<void> {
    this.shuttingDown = true;
    await this.stop();
  }
}
