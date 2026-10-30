import { AnalyticsQuery } from "../src/analytics-contract";
import { SkillsJournal } from "./skills-journal";
import { createHash, randomUUID } from "node:crypto";
import {
  PracticeInputSchema,
  DebriefInputSchema,
  SubmissionInputSchema,
  SubmissionActionSchema,
  DraftInputSchema,
  PresentationIdSchema,
  type DraftInput,
  PracticeErrorSchema,
  type PracticeIntent,
  type PracticeResult,
  type PracticeState,
} from "../src/practice-contract";
import { join } from "node:path";
import { SubmissionJournal } from "./submission-journal";
import { DraftJournal } from "./draft-journal";
import { PracticeSession } from "./practice-session";
import { PracticeJournal } from "./practice-journal";

/** Explicit live opt-in, independent of learner snapshot consent. Bodies are never journaled. */
export class PracticeOwner {
  private current?: PracticeSession;
  private generation = 0;
  private busy = false;
  private closing?: Promise<void>;
  private readonly drafts: DraftJournal;
  private readonly submissions: SubmissionJournal;
  private readonly skills: SkillsJournal;
  constructor(
    private readonly create: () => PracticeSession,
    private readonly journal: PracticeJournal,
  ) {
    this.skills = new SkillsJournal(join(journal.directory, "skills"));
    this.submissions = new SubmissionJournal(
      join(journal.directory, "submissions"),
    );
    this.drafts = new DraftJournal(join(journal.directory, "drafts"));
  }
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
      const recent = await selected.request({
        method: "practice.presentations",
        ...identity,
      });
      if (!authorized() || generation !== this.generation)
        return { ok: false, code: "CANCELLED" };
      if (!recent.ok) return recent;
      if (recent.method !== "practice.presentations")
        return { ok: false, code: "PROTOCOL_ERROR" };
      const pendingSave = this.drafts.read();
      if (pendingSave && pendingSave.profileId !== identity.profileId)
        return { ok: false, code: "JOURNAL_INVALID" };
      const pendingSubmission = this.submissions.read();
      if (
        pendingSubmission &&
        pendingSubmission.profileId !== identity.profileId
      )
        return { ok: false, code: "JOURNAL_INVALID" };
      return {
        ok: true,
        ...identity,
        candidates: result.candidates,
        pending,
        presentations: recent.presentations,
        pendingSave,
        pendingSubmission,
      };
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
  async resume(
    presentationId: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const id = PresentationIdSchema.safeParse(presentationId);
    if (!id.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, (s, i) =>
      s.request({
        method: "practice.presentation.resume",
        ...i,
        presentationId: id.data,
      }),
    );
  }
  async readDraft(
    presentationId: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const id = PresentationIdSchema.safeParse(presentationId);
    if (!id.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, (s, i) =>
      s.request({
        method: "practice.draft.read",
        ...i,
        presentationId: id.data,
      }),
    );
  }
  async saveDraft(
    input: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const parsed = DraftInputSchema.safeParse(input);
    if (!parsed.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, async (s, i) => {
      const digest = draftDigest(parsed.data);
      let intent = this.drafts.read();
      if (
        intent &&
        (intent.profileId !== i.profileId || intent.digest !== digest)
      )
        return { ok: false, code: "RECOVERY_REQUIRED" };
      if (!intent) {
        intent = {
          v: 1,
          profileId: i.profileId,
          commandId: randomUUID(),
          presentationId: parsed.data.presentationId,
          expectedRevision: parsed.data.expectedRevision,
          digest,
        };
        this.drafts.write(intent);
      }
      const result = await s.request({
        method: "practice.draft.save",
        ...i,
        commandId: intent.commandId,
        ...parsed.data,
      });
      if (!authorized() || this.current !== s)
        return { ok: false, code: "CANCELLED" };
      if (result.ok && result.method === "practice.draft.save") {
        if (result.saved.digest !== digest)
          return { ok: false, code: "PROTOCOL_ERROR" };
        this.drafts.clear(intent.commandId);
      }
      return result;
    });
  }
  async reconcileDraft(
    presentationId: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const id = PresentationIdSchema.safeParse(presentationId);
    if (!id.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, async (s, i) => {
      const intent = this.drafts.read();
      if (!intent)
        return s.request({
          method: "practice.draft.read",
          ...i,
          presentationId: id.data,
        });
      if (intent.profileId !== i.profileId || intent.presentationId !== id.data)
        return { ok: false, code: "RECOVERY_REQUIRED" };
      const result = await s.request({
        method: "practice.draft.status",
        ...i,
        commandId: intent.commandId,
        presentationId: id.data,
      });
      if (!authorized() || this.current !== s)
        return { ok: false, code: "CANCELLED" };
      if (result.ok && result.method === "practice.draft.status") {
        if (result.saved && result.saved.digest !== intent.digest)
          return { ok: false, code: "PROTOCOL_ERROR" };
        // No receipt under exclusive owner means this command did not commit. Explicit reconciliation only.
        this.drafts.clear(intent.commandId);
      }
      return result;
    });
  }
  async prepareSubmission(
    input: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const parsed = SubmissionInputSchema.safeParse(input);
    if (!parsed.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, async (s, i) => {
      if (this.submissions.read() || this.drafts.read())
        return { ok: false, code: "RECOVERY_REQUIRED" };
      const intent = {
        v: 1 as const,
        profileId: i.profileId,
        commandId: randomUUID(),
        ...parsed.data,
      };
      this.submissions.write(intent);
      const result = await s.request({
        method: "practice.submission.prepare",
        ...i,
        commandId: intent.commandId,
        ...parsed.data,
      });
      if (!authorized() || this.current !== s)
        return { ok: false, code: "CANCELLED" };
      // Clear only a validated durable receipt. Errors/unknown completion require explicit status.
      if (result.ok && "evaluation" in result && result.evaluation)
        this.submissions.clear(intent.commandId);
      return result;
    });
  }
  async submissionAction(
    input: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const parsed = SubmissionActionSchema.safeParse(input);
    if (!parsed.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, async (s, i) => {
      const { action, ...context } = parsed.data;
      const intent = this.submissions.read();
      if (
        intent &&
        (intent.profileId !== i.profileId ||
          intent.commandId !== context.commandId ||
          intent.presentationId !== context.presentationId ||
          action !== "status")
      )
        return { ok: false, code: "RECOVERY_REQUIRED" };
      const result = await s.request({
        method: `practice.submission.${action}`,
        ...i,
        ...context,
      });
      if (!authorized() || this.current !== s)
        return { ok: false, code: "CANCELLED" };
      if (intent && result.ok && "evaluation" in result) {
        if (
          result.evaluation &&
          (result.evaluation.draftRevision !== intent.expectedRevision ||
            result.evaluation.assistance !== intent.assistance)
        )
          return { ok: false, code: "PROTOCOL_ERROR" };
        // Absence is definitive under this exclusive owner. Never reprepare from today's draft.
        this.submissions.clear(intent.commandId);
      }
      return result;
    });
  }
  async readDebrief(
    input: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const parsed = DebriefInputSchema.safeParse(input);
    if (!parsed.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, (s, i) =>
      s.request({
        method: "practice.submission.debrief",
        ...i,
        ...parsed.data,
      }),
    );
  }
  async submissionsFor(
    presentationId: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    const id = PresentationIdSchema.safeParse(presentationId);
    if (!id.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, (s, i) =>
      s.request({
        method: "practice.submissions",
        ...i,
        presentationId: id.data,
      }),
    );
  }
  async analytics(
    input: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    const query = AnalyticsQuery.safeParse(input);
    if (!query.success) return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, (s, i) =>
      s.request({ method: "practice.analytics.read", ...i, ...query.data }),
    );
  }
  async skillAction(
    action: unknown,
    profileId: unknown,
    sessionId: unknown,
    authorized: () => boolean,
  ): Promise<PracticeResult> {
    if (!authorized()) return { ok: false, code: "UNAVAILABLE" };
    if (action !== "read" && action !== "publish")
      return { ok: false, code: "INVALID_INPUT" };
    return this.perform(profileId, sessionId, authorized, async (s, i) => {
      const pending = this.skills.read();
      if (pending) {
        if (pending.profileId !== i.profileId)
          return { ok: false, code: "JOURNAL_INVALID" };
        // Recovery checks the original command only; never re-dispatch publication.
        const result = await s.request({
          method: "practice.skills.status",
          ...i,
          commandId: pending.commandId,
          originSessionId: pending.originSessionId,
        });
        if (!authorized() || this.current !== s)
          return { ok: false, code: "CANCELLED" };
        if (result.ok && result.method === "practice.skills.status")
          this.skills.clear(pending.commandId);
        return result;
      }
      if (action === "read")
        return s.request({ method: "practice.skills.state", ...i });
      const intent = {
        v: 1 as const,
        profileId: i.profileId,
        originSessionId: i.sessionId,
        commandId: randomUUID(),
        operation: "pilot-ordinal-v1" as const,
      };
      this.skills.write(intent);
      const result = await s.request({
        method: "practice.skills.reconcile",
        ...i,
        commandId: intent.commandId,
        originSessionId: intent.originSessionId,
      });
      if (!authorized() || this.current !== s)
        return { ok: false, code: "CANCELLED" };
      if (result.ok && result.method === "practice.skills.reconcile")
        this.skills.clear(intent.commandId);
      return result;
    });
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

export function draftDigest(input: DraftInput): string {
  const hash = createHash("sha256");
  for (const value of [
    input.presentationId,
    String(input.expectedRevision),
    input.source,
    input.reflection,
  ]) {
    const bytes = Buffer.from(value, "utf8"),
      length = Buffer.alloc(4);
    length.writeUInt32BE(bytes.length);
    hash.update(length);
    hash.update(bytes);
  }
  return hash.digest("hex");
}
