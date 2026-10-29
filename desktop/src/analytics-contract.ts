import { z } from "zod";
const ref = z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/);
const hash = z.string().regex(/^[0-9a-f]{64}$/);
const number = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
export const AnalyticsQuery = z
  .strictObject({
    page: number.max(63),
    snapshot: z.union([z.literal(""), hash]),
  })
  .refine((q) => (q.page === 0 ? q.snapshot === "" : q.snapshot !== ""));
export const AnalyticsCommand = z
  .strictObject({
    method: z.literal("practice.analytics.read"),
    profileId: z.string().uuid(),
    sessionId: z.string().uuid(),
    page: number.max(63),
    snapshot: z.union([z.literal(""), hash]),
  })
  .refine((q) => (q.page === 0 ? q.snapshot === "" : q.snapshot !== ""));
const codes = z.array(ref).max(64);
export const AnalyticsResponse = z
  .strictObject({
    v: z.literal(1),
    id: z.string().uuid(),
    ok: z.literal(true),
    method: z.literal("practice.analytics.read"),
    profileId: z.string().uuid(),
    sessionId: z.string().uuid(),
    capturedAt: z.string().datetime(),
    snapshot: hash,
    page: number.max(63),
    more: z.boolean(),
    graphId: z.literal("java-debugging-concurrency"),
    graphVersion: z.literal(1),
    skills: z
      .array(
        z.strictObject({
          id: ref,
          version: z.literal(1),
          name: z.string().min(1).max(160),
          behaviors: z.array(z.string().min(1).max(1024)).length(1),
        }),
      )
      .length(4),
    edges: z.array(z.strictObject({ from: ref, to: ref })).length(3),
    revisions: z
      .array(
        z.strictObject({
          id: number.positive(),
          skill: ref,
          version: number.positive(),
          graphVersion: number.positive(),
          policy: ref,
          asOf: z.string().datetime(),
          epoch: ref,
          status: z.enum(["HISTORICAL", "INCOMPATIBLE", "UNAVAILABLE"]),
          warnings: codes,
          interpretation: z
            .strictObject({
              band: z.enum([
                "UNKNOWN",
                "DEVELOPING",
                "INDEPENDENT",
                "TRANSFER_SUPPORTED",
                "RETENTION_SUPPORTED",
              ]),
              support: z.enum(["INSUFFICIENT", "LIMITED", "DIVERSE"]),
              freshness: z.enum(["CURRENT", "STALE", "NO_OBSERVATION"]),
              contradicted: z.boolean(),
              families: number.max(10000),
              coverage: z
                .array(
                  z.enum([
                    "SUPPORTED",
                    "UNSUPPORTED",
                    "CONTRADICTED",
                    "EXCLUDED",
                    "NOT_EVALUATED",
                  ]),
                )
                .length(1),
              reasons: codes,
              explanations: z.array(z.string().min(1).max(2048)).max(64),
              exclusions: codes,
              evidence: z.array(hash).max(64),
              evidenceTruncated: z.boolean(),
            })
            .nullable(),
        }),
      )
      .max(16),
    events: z
      .array(
        z.strictObject({
          sequence: number.positive(),
          id: hash,
          root: hash,
          revision: number.positive(),
          recordedAt: z.string().datetime(),
          observedAt: z.string().datetime().nullable(),
          change: z.enum(["ORIGINAL", "SUPERSESSION", "RETRACTION"]),
          provenance: z.enum([
            "LOCAL_CHECK",
            "OBJECTIVE_REVIEW",
            "SELF_REPORTED",
            "LEGACY_UNSPECIFIED",
            "AUTHENTICATED_EXTERNAL",
          ]),
          outcome: z.enum(["PASS", "FAIL", "PARTIAL", "UNAVAILABLE"]),
          assistance: z.enum([
            "UNKNOWN",
            "HINT",
            "NO_IN_APP_HINT_RECORDED",
            "NONE_OBSERVED",
            "EXPLANATION_OR_SOLUTION",
            "DECLARED_EXTERNAL",
          ]),
          exposure: z.enum([
            "UNKNOWN",
            "FAMILIAR_RECALL",
            "REPEAT",
            "RELATED_VARIANT",
            "FIRST_PRESENTATION",
            "HELD_OUT_TRANSFER",
            "DELAYED_RETENTION",
          ]),
          availability: z.enum([
            "AVAILABLE",
            "SOURCE_UNAVAILABLE",
            "RETRACTED",
            "UNSUPPORTED_VERSION",
          ]),
          head: z.boolean(),
          skills: z.array(ref).max(4),
        }),
      )
      .max(32),
  })
  .refine(
    (r) =>
      new Set(r.skills.map((s) => s.id)).size === 4 &&
      new Set(r.revisions.map((s) => s.id)).size === r.revisions.length &&
      new Set(r.events.map((s) => s.id)).size === r.events.length &&
      r.edges.every(
        (e) =>
          r.skills.some((s) => s.id === e.from) &&
          r.skills.some((s) => s.id === e.to),
      ) &&
      r.revisions.every(
        (e) =>
          (e.status === "HISTORICAL") === (e.interpretation !== null) &&
          (!e.interpretation ||
            (e.graphVersion === 1 &&
              e.version === 1 &&
              e.policy === "ordinal-v1" &&
              r.skills.some((s) => s.id === e.skill))),
      ) &&
      r.events.every((e) =>
        e.skills.every((id) => r.skills.some((s) => s.id === id)),
      ),
  );
export type AnalyticsData = z.infer<typeof AnalyticsResponse>;
export type AnalyticsInput = z.infer<typeof AnalyticsQuery>;
export type AnalyticsRevision = AnalyticsData["revisions"][number];
