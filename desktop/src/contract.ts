import { z } from "zod";

export const MAX_FRAME_BYTES = 65_536;
export const MAX_REQUEST_BYTES = 16_384;
const uuid = z
  .string()
  .regex(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
const text = (limit: number) =>
  z
    .string()
    .max(limit)
    .refine(
      (value) => !/[\u0000-\u001f\u007f-\u009f]|\p{Surrogate}/u.test(value),
    );
export const DeckInputSchema = z.strictObject({
  name: text(256)
    .min(1)
    .refine((value) =>
      /[^\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]/u.test(
        value,
      ),
    ),
  description: text(2048),
});
export type DeckInput = z.infer<typeof DeckInputSchema>;
export const DeckSchema = z.strictObject({
  id: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
  name: text(256).min(1),
  description: text(2048),
});
export const StateSchema = z.strictObject({
  kind: z.literal("state"),
  source: z.literal("experimental-profile"),
  profileId: uuid,
  readOnly: z.literal(true),
  progression: z.strictObject({ status: z.literal("unsupported") }),
  decks: z.array(DeckSchema).max(8),
  nextAfterId: z
    .number()
    .int()
    .positive()
    .max(Number.MAX_SAFE_INTEGER)
    .nullable(),
});
export const ReceiptSchema = z.discriminatedUnion("status", [
  z.strictObject({
    kind: z.literal("receipt"),
    commandId: uuid,
    status: z.literal("created"),
    deck: DeckSchema,
  }),
  z.strictObject({
    kind: z.literal("receipt"),
    commandId: uuid,
    status: z.literal("rejected"),
    code: z.literal("NAME_EXISTS"),
  }),
]);
export const StatusSchema = z.strictObject({
  kind: z.literal("command-status"),
  commandId: uuid,
  receipt: ReceiptSchema.nullable(),
});
export const ErrorCodeSchema = z.enum([
  "START_FAILED",
  "UNAVAILABLE",
  "READ_FAILED",
  "COMMAND_FAILED",
  "PROTOCOL_ERROR",
  "TIMEOUT",
  "CANCELLED",
  "BUSY",
  "INVALID_INPUT",
  "PROFILE_BUSY",
  "PROFILE_INVALID",
  "PROFILE_MISMATCH",
  "COMMAND_ID_CONFLICT",
  "JOURNAL_INVALID",
  "RECOVERY_REQUIRED",
]);
export type ErrorCode = z.infer<typeof ErrorCodeSchema>;
export const FailureSchema = z.strictObject({
  ok: z.literal(false),
  code: ErrorCodeSchema,
});
export const PayloadSchema = z.discriminatedUnion("kind", [
  StateSchema,
  ReceiptSchema,
  StatusSchema,
]);
export const ResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ok: z.literal(true), result: PayloadSchema }),
  FailureSchema,
]);
const read = z.strictObject({
  method: z.literal("state.read"),
  afterId: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
});
const create = z.strictObject({
  method: z.literal("decks.create"),
  profileId: uuid,
  commandId: uuid,
  ...DeckInputSchema.shape,
});
const status = z.strictObject({
  method: z.literal("commands.status"),
  profileId: uuid,
  commandId: uuid,
});
export const CommandSchema = z.discriminatedUnion("method", [
  read,
  create,
  status,
]);
const envelope = { v: z.literal(2), id: uuid };
export const RequestSchema = z.discriminatedUnion("method", [
  read.extend(envelope),
  create.extend(envelope),
  status.extend(envelope),
]);
export const WireResponseSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ...envelope, ok: z.literal(true), result: PayloadSchema }),
  FailureSchema.extend(envelope),
]);
export const ReadySchema = z.discriminatedUnion("type", [
  z.strictObject({
    v: z.literal(2),
    type: z.literal("ready"),
    profileId: uuid,
  }),
  z.strictObject({
    v: z.literal(2),
    type: z.literal("startup-error"),
    code: z.enum(["PROFILE_BUSY", "PROFILE_INVALID"]),
  }),
]);
export const IntentSchema = z.strictObject({
  v: z.literal(1),
  profileId: uuid,
  commandId: uuid,
  input: DeckInputSchema,
});
export const OperationSchema = IntentSchema.extend({
  receipt: ReceiptSchema.nullable(),
  error: ErrorCodeSchema.nullable(),
});
export const OperationResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({
    ok: z.literal(true),
    operation: OperationSchema.nullable(),
  }),
  FailureSchema,
]);
export const AckResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ok: z.literal(true) }),
  FailureSchema,
]);
export type State = z.infer<typeof StateSchema>;
export type Receipt = z.infer<typeof ReceiptSchema>;
export type Command = z.infer<typeof CommandSchema>;
export type Result = z.infer<typeof ResultSchema>;
export type Intent = z.infer<typeof IntentSchema>;
export type Operation = z.infer<typeof OperationSchema>;
export type OperationResult = z.infer<typeof OperationResultSchema>;
export type AckResult = z.infer<typeof AckResultSchema>;
export interface CodeFitBridge {
  preparePracticeSubmission(
    input: import("./practice-contract").SubmissionInput,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  practiceSubmissionAction(
    input: import("./practice-contract").SubmissionAction,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  listPracticeSubmissions(
    presentationId: string,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;

  setPracticeDraftDirty(dirty: boolean): Promise<void>;
  resumePractice(
    presentationId: string,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  readPracticeDraft(
    presentationId: string,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  savePracticeDraft(
    input: import("./practice-contract").DraftInput,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  reconcilePracticeDraft(
    presentationId: string,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  connectPractice(): Promise<import("./practice-contract").PracticeState>;
  openAuthorizedPracticeChallenge(
    input: import("./practice-contract").PracticeInput,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  recoverPractice(
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  acknowledgePractice(
    commandId: string,
    profileId: string,
    sessionId: string,
  ): Promise<import("./practice-contract").PracticeResult>;
  disconnectPractice(): Promise<void>;
  readLearnerMissions(): Promise<
    import("./learner-contract").LearnerMissionsResult
  >;
  readCurriculumPreview(): Promise<
    import("./curriculum-contract").CurriculumResult
  >;
  cancelCurriculumPreview(): Promise<void>;
  chooseLearnerProfile(): Promise<import("./learner-contract").LearnerResult>;
  readLearnerSkills(): Promise<
    import("./learner-contract").LearnerSkillsResult
  >;
  readLearnerProfile(): Promise<import("./learner-contract").LearnerResult>;
  disconnectLearnerProfile(): Promise<void>;
  readState(afterId: number): Promise<Result>;
  createDeck(input: DeckInput): Promise<OperationResult>;
  pendingDeck(): Promise<OperationResult>;
  recoverDeck(): Promise<OperationResult>;
  acknowledgeDeck(commandId: string): Promise<AckResult>;
  cancelOperation(): Promise<void>;
}
declare global {
  interface Window {
    codefit: CodeFitBridge;
  }
}
