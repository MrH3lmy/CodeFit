import { randomUUID } from "node:crypto";
import {
  PracticeInputSchema,
  PracticeErrorSchema,
  type PracticeIntent,
  type PracticeResult,
  type PracticeState,
} from "../src/practice-contract";
import { PracticeSession } from "./practice-session";
import { PracticeJournal } from "./practice-journal";

/** Explicit live opt-in, independent of learner snapshot consent. Bodies are never journaled. */
export class PracticeOwner {
  private current?: PracticeSession;
  private generation = 0;
  private busy = false;
  private closing?: Promise<void>;
  constructor(
    private readonly create: () => PracticeSession,
    private readonly journal: PracticeJournal,
  ) {}
  async connect(authorized: () => boolean): Promise<PracticeState> {
    if (!authorized() || process.platform !== "linux")
      return { ok: false, code: "UNAVAILABLE" };
    if (this.busy) return { ok: false, code: "BUSY" };
    this.busy = true;
    const generation = this.generation;
    try {
      if (this.closing) await this.closing;
      if (!authorized() || generation !== this.generation)
        return { ok: false, code: "CANCELLED" };
      const selected = (this.current ??= this.create());
      const identity = await selected.identity();
      const result = await selected.request({
        method: "practice.list",
        ...identity,
      });
      if (!authorized() || generation !== this.generation)
        return { ok: false, code: "CANCELLED" };
      if (!result.ok) {
        await this.disconnect();
        return result;
      }
      if (result.method !== "practice.list")
        return { ok: false, code: "PROTOCOL_ERROR" };
      const pending = this.journal.read();
      if (pending && pending.profileId !== identity.profileId)
        return { ok: false, code: "JOURNAL_INVALID" };
      return { ok: true, ...identity, candidates: result.candidates, pending };
    } catch (error) {
      await this.disconnect();
      const code = PracticeErrorSchema.safeParse(
        error instanceof Error ? error.message : null,
      );
      return { ok: false, code: code.success ? code.data : "UNAVAILABLE" };
    } finally {
      this.busy = false;
    }
  }
  async open(
    input: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const parsed = PracticeInputSchema.safeParse(input);
    if (!parsed.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(
      profileId,
      sessionId,
      authorized,
      async (session, identity) => {
        if (this.journal.read())
          return { ok: false, code: "RECOVERY_REQUIRED" };
        const intent: PracticeIntent = {
          v: 1,
          profileId: identity.profileId,
          commandId: randomUUID(),
          input: parsed.data,
        };
        this.journal.write(intent);
        return session.request({
          method: "practice.open",
          ...identity,
          commandId: intent.commandId,
          ...intent.input,
        });
      },
    );
  }
  async recover(
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    return this.perform(
      profileId,
      sessionId,
      authorized,
      async (session, identity) => {
        const intent = this.journal.read();
        if (!intent || intent.profileId !== identity.profileId)
          return { ok: false, code: "RECOVERY_REQUIRED" };
        const checked = await session.request({
          method: "practice.status",
          ...identity,
          commandId: intent.commandId,
        });
        if (!checked.ok) return checked;
        if (!authorized() || this.current !== session)
          return { ok: false, code: "CANCELLED" };
        // Explicit recovery reuses the immutable command even when no exposure committed.
        return session.request({
          method: "practice.open",
          ...identity,
          commandId: intent.commandId,
          ...intent.input,
        });
      },
    );
  }
  async acknowledge(
    commandId: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    return this.perform(
      profileId,
      sessionId,
      authorized,
      async (session, identity) => {
        const intent = this.journal.read();
        if (
          !intent ||
          intent.commandId !== commandId ||
          intent.profileId !== identity.profileId
        )
          return { ok: false, code: "RECOVERY_REQUIRED" };
        const result = await session.request({
          method: "practice.status",
          ...identity,
          commandId: intent.commandId,
        });
        if (
          result.ok &&
          result.method === "practice.status" &&
          result.receipt &&
          authorized() &&
          this.current === session
        )
          this.journal.clear(intent.commandId);
        return result;
      },
    );
  }
  private async perform(
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
    action: (
      s: PracticeSession,
      i: { profileId: string; sessionId: string },
    ) => Promise<PracticeResult>,
  ): Promise<PracticeResult> {
    if (!authorized() || !this.current)
      return { ok: false, code: "UNAVAILABLE" };
    if (this.busy) return { ok: false, code: "BUSY" };
    this.busy = true;
    const generation = this.generation,
      selected = this.current;
    try {
      const identity = await selected.identity();
      if (
        !authorized() ||
        generation !== this.generation ||
        profileId !== identity.profileId ||
        sessionId !== identity.sessionId
      )
        return { ok: false, code: "SESSION_MISMATCH" };
      const result = await action(selected, identity);
      if (!authorized() || generation !== this.generation)
        return { ok: false, code: "CANCELLED" };
      return result;
    } catch {
      return { ok: false, code: "JOURNAL_INVALID" };
    } finally {
      this.busy = false;
    }
  }
  async disconnect() {
    this.generation++;
    const selected = this.current;
    this.current = undefined;
    if (selected) {
      const done = selected.stop();
      this.closing = done;
      try {
        await done;
      } finally {
        if (this.closing === done) this.closing = undefined;
      }
    } else if (this.closing) await this.closing;
  }
}
