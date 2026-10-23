import { z } from "zod";

const text = (max: number) =>
  z
    .string()
    .min(1)
    .max(max)
    .refine(
      (v) => !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(v),
    );
const integer = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
const ref = z.strictObject({
  id: z
    .string()
    .max(128)
    .regex(/^[a-z0-9]+(?:[.-][a-z0-9]+)*$/),
  version: integer.min(1),
});
const band = z.enum([
  "UNKNOWN",
  "DEVELOPING",
  "INDEPENDENT",
  "TRANSFER_SUPPORTED",
  "RETENTION_SUPPORTED",
]);
export const ReasonCodeSchema = z.enum([
  "NO_DIRECT_EVIDENCE",
  "UNAVAILABLE_SOURCE",
  "SUPERSEDED",
  "MAPPING_UNAVAILABLE",
  "UNKNOWN_TIME",
  "FUTURE_EVENT",
  "BEFORE_EPOCH",
  "CORRELATED",
  "UNCERTAIN_PROVENANCE",
  "UNAVAILABLE_EVALUATION",
  "INVALID_EVALUATION",
  "ASSISTED",
  "UNKNOWN_ASSISTANCE",
  "FAMILIAR",
  "UNKNOWN_EXPOSURE",
  "DIAGNOSTIC_SCOPE",
  "MISSING_BEHAVIORS",
  "OBSERVED_PROGRESS",
  "INDEPENDENT_OPPORTUNITY",
  "NEED_DIVERSITY",
  "INDEPENDENT_FAMILIES",
  "TRANSFER",
  "RETENTION",
  "RETENTION_NOT_DELAYED",
  "CONTRADICTED",
  "REPEATED_FAILURE",
  "RESTORATION_PENDING",
  "STALE",
  "INDEPENDENT_VALIDATION_DUE",
  "CURRENT_SCOPE",
]);
const warning = z.enum([
  "EVIDENCE_WITHDRAWN",
  "EVIDENCE_REVISED",
  "EVIDENCE_MISSING",
  "MAPPING_CHANGED",
  "EPOCH_CHANGED",
]);
const counts = <T extends z.ZodType<string>>(code: T, max: number) =>
  z.array(z.strictObject({ code, count: integer.max(512) })).max(max);
const estimate = z
  .strictObject({
    status: z.enum(["historical", "missing", "unavailable"]),
    reason: z
      .enum([
        "REVISION_INVALID",
        "POLICY_UNAVAILABLE",
        "LIMIT_EXCEEDED",
        "GRAPH_MISMATCH",
      ])
      .nullable(),
    revision: integer.min(1).nullable(),
    asOf: z.iso.datetime().nullable(),
    policyVersion: text(128).nullable(),
    epochId: text(128).nullable(),
    band: band.nullable(),
    historicalBand: band.nullable(),
    support: z.enum(["INSUFFICIENT", "LIMITED", "DIVERSE"]).nullable(),
    freshness: z.enum(["NO_OBSERVATION", "CURRENT", "STALE"]).nullable(),
    contradicted: z.boolean().nullable(),
    regressionCap: z.boolean().nullable(),
    currentSupport: z.literal("NOT_EVALUATED"),
    independentFamilies: integer.max(512).nullable(),
    supportedBehaviors: z.array(integer.max(15)).max(16),
    lastMeaningfulObservation: z.iso.datetime().nullable(),
    contributionCount: integer.max(512),
    exclusionCount: integer.max(512),
    provenanceCounts: counts(
      z.enum([
        "LOCAL_CHECK",
        "OBJECTIVE_REVIEW",
        "SELF_REPORTED",
        "LEGACY_UNSPECIFIED",
        "AUTHENTICATED_EXTERNAL",
      ]),
      5,
    ),
    assistanceCounts: counts(
      z.enum([
        "UNKNOWN",
        "HINT",
        "NO_IN_APP_HINT_RECORDED",
        "NONE_OBSERVED",
        "EXPLANATION_OR_SOLUTION",
        "DECLARED_EXTERNAL",
      ]),
      6,
    ),
    exposureCounts: counts(
      z.enum([
        "UNKNOWN",
        "FAMILIAR_RECALL",
        "REPEAT",
        "RELATED_VARIANT",
        "FIRST_PRESENTATION",
        "HELD_OUT_TRANSFER",
        "DELAYED_RETENTION",
      ]),
      7,
    ),
    reasons: z.array(ReasonCodeSchema).max(31),
    warnings: z.array(warning).max(5),
  })
  .superRefine((v, ctx) => {
    if (
      v.status === "historical" &&
      [
        v.revision,
        v.asOf,
        v.policyVersion,
        v.epochId,
        v.band,
        v.historicalBand,
        v.support,
        v.freshness,
        v.contradicted,
        v.regressionCap,
        v.independentFamilies,
      ].some((x) => x === null)
    )
      ctx.addIssue({
        code: "custom",
        message: "Incomplete historical interpretation",
      });
    if (
      v.status !== "historical" &&
      (v.band !== null ||
        v.support !== null ||
        v.freshness !== null ||
        v.supportedBehaviors.length ||
        v.contributionCount)
    )
      ctx.addIssue({
        code: "custom",
        message: "Unavailable interpretation carries support",
      });
  });
export const LearnerSkillsSchema = z
  .strictObject({
    v: z.literal(1),
    kind: z.literal("learner-skills"),
    source: z.literal("learner-snapshot"),
    profileId: z.string().regex(/^[0-9a-f]{64}$/),
    sessionId: z.string().uuid(),
    capturedAt: z.iso.datetime(),
    schemaVersion: z.literal(25),
    readOnly: z.literal(true),
    status: z.enum(["available", "unavailable"]),
    reason: z
      .enum(["GRAPH_UNAVAILABLE", "GRAPH_INVALID", "LIMIT_EXCEEDED"])
      .nullable(),
    graph: ref.nullable(),
    newerGraphAvailable: z.boolean(),
    nodes: z
      .array(
        z.strictObject({
          ref,
          domainId: text(128),
          name: text(160),
          description: text(2048),
          behaviors: z.array(text(1024)).min(1).max(16),
          lifecycle: z.enum(["ACTIVE", "RETIRED"]),
          estimate,
        }),
      )
      .max(32),
    edges: z
      .array(z.strictObject({ prerequisite: ref, dependent: ref }))
      .max(128),
  })
  .superRefine((v, ctx) => {
    const bad = () =>
      ctx.addIssue({ code: "custom", message: "Inconsistent exact graph" });
    if (v.status === "unavailable") {
      if (
        v.nodes.length ||
        v.edges.length ||
        v.graph !== null ||
        v.reason === null
      )
        bad();
      return;
    }
    if (!v.graph || !v.nodes.length || v.reason !== null) bad();
    const keys = new Set(v.nodes.map((n) => `${n.ref.id}@${n.ref.version}`));
    if (
      keys.size !== v.nodes.length ||
      new Set(v.nodes.map((n) => n.ref.id)).size !== v.nodes.length
    )
      bad();
    const edges = new Set<string>();
    for (const e of v.edges) {
      const p = `${e.prerequisite.id}@${e.prerequisite.version}`,
        d = `${e.dependent.id}@${e.dependent.version}`,
        key = `${p}>${d}`;
      if (!keys.has(p) || !keys.has(d) || p === d || edges.has(key)) bad();
      edges.add(key);
    }
    for (const n of v.nodes)
      if (
        n.estimate.supportedBehaviors.some((b) => b >= n.behaviors.length) ||
        (n.estimate.asOf &&
          Date.parse(n.estimate.asOf) > Date.parse(v.capturedAt))
      )
        bad();
    // Validate topology independently; frontend layout never repairs malformed graph data.
    const remaining = new Set(v.nodes.map((n) => n.ref.id));
    while (remaining.size) {
      const roots = [...remaining].filter(
        (id) =>
          !v.edges.some(
            (e) => e.dependent.id === id && remaining.has(e.prerequisite.id),
          ),
      );
      if (!roots.length) {
        bad();
        break;
      }
      roots.forEach((id) => remaining.delete(id));
    }
  });
export type LearnerSkills = z.infer<typeof LearnerSkillsSchema>;
export type LearnerSkill = LearnerSkills["nodes"][number];
