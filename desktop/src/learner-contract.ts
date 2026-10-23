import { z } from "zod";
import { ErrorCodeSchema } from "./contract";
import { LearnerSkillsSchema } from "./skill-contract";

export const LearnerErrorSchema = z.enum([
  ...ErrorCodeSchema.options,
  "INCOMPATIBLE_SCHEMA",
  "SOURCE_CHANGED",
  "SNAPSHOT_LIMIT",
  "SNAPSHOT_FAILED",
  "UNSUPPORTED_PLATFORM",
]);
export type LearnerError = z.infer<typeof LearnerErrorSchema>;
const id = z
  .string()
  .regex(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
const profileId = z.string().regex(/^[0-9a-f]{64}$/);
const integer = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const unavailable = z.strictObject({ status: z.literal("unavailable") });
export const LearnerProjectionSchema = z.strictObject({
  v: z.literal(1),
  kind: z.literal("learner-overview"),
  source: z.literal("learner-snapshot"),
  profileId,
  sessionId: id,
  capturedAt: z.iso.datetime(),
  schemaVersion: z.literal(25),
  readOnly: z.literal(true),
  identity: z.discriminatedUnion("status", [
    z.strictObject({
      status: z.literal("available"),
      displayName: z
        .string()
        .min(1)
        .max(120)
        .refine((v) => !/[\u0000-\u001f\u007f-\u009f]/u.test(v)),
    }),
    z.strictObject({ status: z.literal("unavailable"), displayName: z.null() }),
  ]),
  progression: z.discriminatedUnion("status", [
    z.strictObject({
      status: z.literal("available"),
      xp: integer,
      level: integer.min(1),
    }),
    z.strictObject({
      status: z.literal("unavailable"),
      xp: z.null(),
      level: z.null(),
    }),
  ]),
  activity: z.strictObject({
    reviewCount: integer,
    dueScheduledCards: integer,
    reviewDay: z.iso.date(),
  }),
  mastery: unavailable,
  recommendations: unavailable,
  missions: unavailable,
});
export type LearnerProjection = z.infer<typeof LearnerProjectionSchema>;
export const LearnerResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ok: z.literal(true), result: LearnerProjectionSchema }),
  z.strictObject({ ok: z.literal(false), code: LearnerErrorSchema }),
]);
export type LearnerResult = z.infer<typeof LearnerResultSchema>;
export const LearnerReadySchema = z.discriminatedUnion("type", [
  z.strictObject({
    v: z.literal(3),
    type: z.literal("ready"),
    profileId,
    sessionId: id,
  }),
  z.strictObject({
    v: z.literal(3),
    type: z.literal("startup-error"),
    code: LearnerErrorSchema,
  }),
]);
export const LearnerRequestSchema = z.strictObject({
  v: z.literal(3),
  id,
  method: z.enum(["learner.overview.read", "learner.skills.read"]),
  profileId,
  sessionId: id,
});
export const LearnerWireSchema = z.discriminatedUnion("ok", [
  z.strictObject({
    v: z.literal(3),
    id,
    ok: z.literal(true),
    result: z.union([LearnerProjectionSchema, LearnerSkillsSchema]),
  }),
  z.strictObject({
    v: z.literal(3),
    id,
    ok: z.literal(false),
    code: LearnerErrorSchema,
  }),
]);

export const LearnerSkillsResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ok: z.literal(true), result: LearnerSkillsSchema }),
  z.strictObject({ ok: z.literal(false), code: LearnerErrorSchema }),
]);
export type LearnerSkillsResult = z.infer<typeof LearnerSkillsResultSchema>;
