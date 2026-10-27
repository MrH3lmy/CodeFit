import { z } from "zod";
const uuid = z.string().uuid();
const ref = z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/);
const version = z.number().int().min(1).max(10000);
export const PracticeInputSchema = z.strictObject({
  questId: ref,
  questVersion: version,
  challengeId: ref,
  challengeVersion: version,
});
export const PracticeCandidateSchema = PracticeInputSchema.extend({
  title: z.string().min(1).max(512),
  effortMinutes: z.number().int().min(1).max(180),
});
export const PracticeReceiptSchema = PracticeInputSchema.extend({
  commandId: uuid,
  presentationId: ref,
  presentedAt: z.string().datetime(),
}).refine((p) => p.presentationId === `desktop-practice-${p.commandId}`);
export const PracticeErrorSchema = z.enum([
  "UNAVAILABLE",
  "START_FAILED",
  "PROFILE_BUSY",
  "PROFILE_INVALID",
  "SESSION_MISMATCH",
  "COMMAND_ID_CONFLICT",
  "NOT_ELIGIBLE",
  "NOT_AUTHORIZED",
  "CONTENT_UNAVAILABLE",
  "OPERATION_UNAVAILABLE",
  "PROTOCOL_ERROR",
  "TIMEOUT",
  "CANCELLED",
  "BUSY",
  "INVALID_INPUT",
  "RECOVERY_REQUIRED",
  "JOURNAL_INVALID",
]);
const identity = { profileId: uuid, sessionId: uuid };
const list = z.strictObject({
  method: z.literal("practice.list"),
  ...identity,
});
const status = z.strictObject({
  method: z.literal("practice.status"),
  ...identity,
  commandId: uuid,
});
const open = z.strictObject({
  method: z.literal("practice.open"),
  ...identity,
  commandId: uuid,
  ...PracticeInputSchema.shape,
});
const presentation = z
  .string()
  .regex(
    /^desktop-practice-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
  );
const revision = z.number().int().min(0).max(9007199254739999);
const unicode = (max: number) =>
  z
    .string()
    .max(max)
    .refine(
      (s) =>
        !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(
          s,
        ),
    );
export const DraftInputSchema = z.strictObject({
  presentationId: presentation,
  expectedRevision: revision,
  source: unicode(32768),
  reflection: unicode(8000),
});
export const DraftSchema = z.strictObject({
  presentationId: presentation,
  revision,
  source: unicode(32768),
  reflection: unicode(8000),
});
export const DraftSavedSchema = z
  .strictObject({
    commandId: uuid,
    presentationId: presentation,
    digest: z.string().regex(/^[0-9a-f]{64}$/),
    expectedRevision: revision,
    revision,
    outcome: z.enum(["SAVED", "CONFLICT"]),
  })
  .refine((r) => r.outcome !== "SAVED" || r.revision > r.expectedRevision);
export const DraftIntentSchema = z.strictObject({
  v: z.literal(1),
  profileId: uuid,
  commandId: uuid,
  presentationId: presentation,
  expectedRevision: revision,
  digest: z.string().regex(/^[0-9a-f]{64}$/),
});
const presentations = z.strictObject({
  method: z.literal("practice.presentations"),
  ...identity,
});
const resume = z.strictObject({
  method: z.literal("practice.presentation.resume"),
  ...identity,
  presentationId: presentation,
});
const draftRead = z.strictObject({
  method: z.literal("practice.draft.read"),
  ...identity,
  presentationId: presentation,
});
const draftSave = z.strictObject({
  method: z.literal("practice.draft.save"),
  ...identity,
  commandId: uuid,
  ...DraftInputSchema.shape,
});
const draftStatus = z.strictObject({
  method: z.literal("practice.draft.status"),
  ...identity,
  commandId: uuid,
  presentationId: presentation,
});
export const PresentationIdSchema = presentation;
export type PracticeDraft = z.infer<typeof DraftSchema>;
export type DraftInput = z.infer<typeof DraftInputSchema>;
export type DraftSaved = z.infer<typeof DraftSavedSchema>;
export type DraftIntent = z.infer<typeof DraftIntentSchema>;
export const PracticeCommandSchema = z.discriminatedUnion("method", [
  list,
  status,
  open,
  presentations,
  resume,
  draftRead,
  draftSave,
  draftStatus,
]);
const envelope = { v: z.literal(1), id: uuid };
export const PracticeRequestSchema = z.discriminatedUnion("method", [
  list.extend(envelope),
  status.extend(envelope),
  open.extend(envelope),
  presentations.extend(envelope),
  resume.extend(envelope),
  draftRead.extend(envelope),
  draftSave.extend(envelope),
  draftStatus.extend(envelope),
]);
const success = { ...envelope, ok: z.literal(true), ...identity };
export const PracticeWireSchema = z.union([
  z.strictObject({
    ...success,
    method: z.literal("practice.presentations"),
    presentations: z.array(PracticeReceiptSchema).max(32),
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.presentation.resume"),
    presentationId: presentation,
    receipt: PracticeReceiptSchema,
    instructions: z.string().min(1).max(24000),
    draft: DraftSchema,
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.draft.read"),
    presentationId: presentation,
    draft: DraftSchema,
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.draft.save"),
    commandId: uuid,
    presentationId: presentation,
    saved: DraftSavedSchema,
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.draft.status"),
    commandId: uuid,
    presentationId: presentation,
    saved: DraftSavedSchema.nullable(),
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.list"),
    candidates: z.array(PracticeCandidateSchema).max(32),
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.status"),
    commandId: uuid,
    receipt: PracticeReceiptSchema.nullable(),
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.open"),
    commandId: uuid,
    receipt: PracticeReceiptSchema,
    instructions: z.string().min(1).max(24000),
  }),
  z.strictObject({
    ...envelope,
    ok: z.literal(false),
    code: PracticeErrorSchema,
  }),
]);
export const PracticeReadySchema = z.discriminatedUnion("type", [
  z.strictObject({ v: z.literal(1), type: z.literal("ready"), ...identity }),
  z.strictObject({
    v: z.literal(1),
    type: z.literal("startup-error"),
    code: z.enum(["PROFILE_BUSY", "PROFILE_INVALID"]),
  }),
]);
export const PracticeIntentSchema = z.strictObject({
  v: z.literal(1),
  profileId: uuid,
  commandId: uuid,
  input: PracticeInputSchema,
});
export type PracticeInput = z.infer<typeof PracticeInputSchema>;
export type PracticeIntent = z.infer<typeof PracticeIntentSchema>;
export type PracticeCommand = z.infer<typeof PracticeCommandSchema>;
export type PracticeWire = z.infer<typeof PracticeWireSchema>;
export type PracticeError = z.infer<typeof PracticeErrorSchema>;
export type PracticeCandidate = z.infer<typeof PracticeCandidateSchema>;
export type PracticeReceipt = z.infer<typeof PracticeReceiptSchema>;
export type PracticeResult = PracticeWire | { ok: false; code: PracticeError };
export type PracticeState =
  | {
      ok: true;
      profileId: string;
      sessionId: string;
      candidates: PracticeCandidate[];
      pending: PracticeIntent | null;
      presentations: PracticeReceipt[];
      pendingSave: DraftIntent | null;
    }
  | { ok: false; code: PracticeError };
