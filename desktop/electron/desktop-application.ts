import { randomUUID } from "node:crypto";
import {
  CommandSchema,
  DeckInputSchema,
  type AckResult,
  type Intent,
  type OperationResult,
  type Result,
} from "../src/contract";
import { EngineOwner } from "./engine-owner";
import { OperationJournal } from "./operation-journal";

/** Application lifecycle only; deck validation, policy and atomic effects remain in Java. */
export class DesktopApplication {
  private busy = false;
  constructor(
    private readonly engine: EngineOwner,
    private readonly journal: OperationJournal,
  ) {}
  read(afterId: unknown, authorized: () => boolean): Promise<Result> {
    const input = CommandSchema.safeParse({ method: "state.read", afterId });
    if (!authorized())
      return Promise.resolve({ ok: false, code: "UNAVAILABLE" });
    if (!input.success)
      return Promise.resolve({ ok: false, code: "INVALID_INPUT" });
    return this.engine.execute(input.data, authorized);
  }
  async create(
    input: unknown,
    authorized: () => boolean,
  ): Promise<OperationResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const validated = DeckInputSchema.safeParse(input);
    if (!validated.success) return { ok: false, code: "INVALID_INPUT" };
    if (this.busy) return { ok: false, code: "BUSY" };
    this.busy = true;
    try {
      if (this.journal.read()) return { ok: false, code: "RECOVERY_REQUIRED" };
      const state = await this.engine.execute(
        { method: "state.read", afterId: 0 },
        authorized,
      );
      if (!state.ok) return state;
      if (state.result.kind !== "state")
        return { ok: false, code: "PROTOCOL_ERROR" };
      if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
      const intent: Intent = {
        v: 1,
        profileId: state.result.profileId,
        commandId: randomUUID(),
        input: validated.data,
      };
      this.journal.write(intent);
      return await this.resolve(intent, true, authorized);
    } catch {
      return { ok: false, code: "JOURNAL_INVALID" };
    } finally {
      this.busy = false;
    }
  }
  async pending(authorized: () => boolean): Promise<OperationResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    try {
      const intent = this.journal.read();
      return intent
        ? await this.resolve(intent, false, authorized)
        : { ok: true, operation: null };
    } catch {
      return { ok: false, code: "JOURNAL_INVALID" };
    }
  }
  async recover(authorized: () => boolean): Promise<OperationResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    if (this.busy) return { ok: false, code: "BUSY" };
    this.busy = true;
    try {
      const intent = this.journal.read();
      if (!intent) return { ok: true, operation: null };
      // Read first; only an explicit recovery action may send the original immutable command again.
      const checked = await this.resolve(intent, false, authorized);
      if (!checked.ok || checked.operation?.receipt || checked.operation?.error)
        return checked;
      return await this.resolve(intent, true, authorized);
    } catch {
      return { ok: false, code: "JOURNAL_INVALID" };
    } finally {
      this.busy = false;
    }
  }
  async acknowledge(
    commandId: unknown,
    authorized: () => boolean,
  ): Promise<AckResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    if (this.busy) return { ok: false, code: "BUSY" };
    this.busy = true;
    try {
      const intent = this.journal.read();
      if (!intent || commandId !== intent.commandId)
        return { ok: false, code: "RECOVERY_REQUIRED" };
      const checked = await this.resolve(intent, false, authorized);
      if (!checked.ok) return checked;
      if (!checked.operation?.receipt || !authorized())
        return { ok: false, code: "RECOVERY_REQUIRED" };
      this.journal.clear(intent.commandId);
      return { ok: true };
    } catch {
      return { ok: false, code: "JOURNAL_INVALID" };
    } finally {
      this.busy = false;
    }
  }
  private async resolve(
    intent: Intent,
    write: boolean,
    authorized: () => boolean,
  ): Promise<OperationResult> {
    const result = await this.engine.execute(
      write
        ? {
            method: "decks.create",
            profileId: intent.profileId,
            commandId: intent.commandId,
            ...intent.input,
          }
        : {
            method: "commands.status",
            profileId: intent.profileId,
            commandId: intent.commandId,
          },
      authorized,
    );
    if (!result.ok)
      return {
        ok: true,
        operation: { ...intent, receipt: null, error: result.code },
      };
    const receipt =
      result.result.kind === "receipt"
        ? result.result
        : result.result.kind === "command-status"
          ? result.result.receipt
          : null;
    return { ok: true, operation: { ...intent, receipt, error: null } };
  }
}
