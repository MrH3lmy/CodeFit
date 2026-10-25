import { z } from "zod";
const ref = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:/-]*$/);
const version = z.number().int().min(1).max(2147483647);
const count = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const status = z.enum(["AVAILABLE", "UNAVAILABLE"]);
export const LearnerMissionsSchema = z
  .strictObject({
    v: z.literal(1),
    kind: z.literal("learner-missions"),
    source: z.literal("learner-snapshot"),
    readOnly: z.literal(true),
    profileId: z.string().regex(/^[0-9a-f]{64}$/),
    sessionId: z.uuidv4(),
    capturedAt: z.iso.datetime(),
    schemaVersion: z.literal(25),
    currentEligibility: z.literal("NOT_EVALUATED"),
    activeAssignment: z.literal("NOT_EVALUATED"),
    actionsEnabled: z.literal(false),
    catalogStatus: z.enum(["AVAILABLE", "INCOMPLETE", "UNAVAILABLE"]),
    quests: z
      .array(
        z.strictObject({
          id: ref,
          version,
          title: z.string().min(1).max(512),
          cohort: z.enum([
            "BASELINE",
            "PRACTICE",
            "TRANSFER",
            "RETENTION",
            "PILOT_POST",
          ]),
        }),
      )
      .max(32),
    historyStatus: status,
    historyTruncated: z.boolean(),
    activities: z
      .array(
        z.strictObject({
          command: ref,
          presentation: ref,
          questId: ref,
          questVersion: version,
          challengeId: ref,
          challengeVersion: version,
          submittedAt: z.iso.datetime(),
          evaluatedAt: z.iso.datetime().nullable(),
          stage: z.enum(["PREPARED", "RUNNING", "EVALUATED", "FINALIZED"]),
          outcome: z
            .enum([
              "PASSED",
              "FAILED",
              "PARTIAL",
              "COMPILE_ERROR",
              "RUNTIME_ERROR",
              "RESOURCE_EXHAUSTED",
              "TIMED_OUT",
              "CANCELLED",
              "INCONCLUSIVE",
              "UNSUPPORTED",
              "EVALUATOR_ERROR",
              "TOOL_UNAVAILABLE",
            ])
            .nullable(),
          revision: version.nullable(),
          withdrawn: z.boolean(),
          metadata: z.enum(["EXACT_PUBLISHED", "UNAVAILABLE"]),
        }),
      )
      .max(20),
    decisionStatus: status,
    decisionsTruncated: z.boolean(),
    decisions: z
      .array(
        z.strictObject({
          id: z.string().regex(/^decision-[0-9a-f]{64}$/),
          asOf: z.iso.datetime(),
        }),
      )
      .max(10),
    review: z.strictObject({
      recordedReviews: count,
      dueScheduledCards: count,
      asOfDay: z.iso.date(),
    }),
  })
  .superRefine((p, ctx) => {
    const bad = () =>
      ctx.addIssue({
        code: "custom",
        message: "Inconsistent recorded history",
      });
    if (
      (p.historyStatus === "UNAVAILABLE" &&
        (p.activities.length || p.historyTruncated)) ||
      (p.catalogStatus === "UNAVAILABLE" && p.quests.length) ||
      (p.decisionStatus === "UNAVAILABLE" &&
        (p.decisions.length || p.decisionsTruncated))
    )
      bad();
    if (
      new Set(p.activities.map((a) => a.command)).size !==
        p.activities.length ||
      new Set(p.quests.map((q) => `${q.id}/${q.version}`)).size !==
        p.quests.length
    )
      bad();
    for (const a of p.activities) {
      if (
        a.evaluatedAt &&
        (Date.parse(a.evaluatedAt) > Date.parse(p.capturedAt) ||
          Date.parse(a.evaluatedAt) < Date.parse(a.submittedAt))
      )
        bad();
      if (
        (a.stage === "FINALIZED") !== (a.revision !== null) ||
        (a.stage === "FINALIZED") !== (a.evaluatedAt !== null) ||
        (a.stage === "FINALIZED" && !a.withdrawn && a.outcome === null) ||
        (a.withdrawn && a.outcome !== null) ||
        (a.stage !== "FINALIZED" && (a.outcome !== null || a.withdrawn)) ||
        Date.parse(a.submittedAt) > Date.parse(p.capturedAt)
      )
        bad();
      if (
        a.metadata === "EXACT_PUBLISHED" &&
        !p.quests.some(
          (q) => q.id === a.questId && q.version === a.questVersion,
        )
      )
        bad();
    }
    if (p.review.asOfDay !== p.capturedAt.slice(0, 10)) bad();
    if (p.decisions.some((d) => Date.parse(d.asOf) > Date.parse(p.capturedAt)))
      bad();
  });
export type LearnerMissions = z.infer<typeof LearnerMissionsSchema>;
