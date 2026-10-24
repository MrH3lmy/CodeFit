import { z } from "zod";

export const CURRICULUM_MAX_BYTES = 32768;
export const PUBLIC_PREVIEW_SHA =
  "95ff42f24a88ac85400a4cda43daf9e0caabab8e4e324fa21c992f78a2ecb874";
const id = z
  .string()
  .max(128)
  .regex(/^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$/);
const hash = z.string().regex(/^[0-9a-f]{64}$/);
const text = z.string().min(1).max(512);
export const CurriculumPreviewSchema = z
  .strictObject({
    schema: z.literal("codefit-public-knowledge-preview/0.1"),
    exposure: z.literal("PUBLIC_PREVIEW"),
    revision: z.literal("v0.41"),
    revisionSha256: z.literal(
      "76377d04ecb0ff45690df32bbc001f66bfaa672e1935327e1c09b2b047a46f52",
    ),
    reviewStatus: z.literal("UNREVIEWED"),
    verification: z.literal("JDK21_VERIFICATION_PENDING"),
    productionImportSupported: z.literal(false),
    graderAvailable: z.literal(false),
    xpEnabled: z.literal(false),
    rewardsEnabled: z.literal(false),
    masteryEnabled: z.literal(false),
    independentApprovals: z.literal(0),
    caveat: text,
    skills: z
      .array(
        z.strictObject({
          id,
          title: text,
          sourceItem: z.string().regex(/^EJ-ITEM-0(?:57|58|59|60)$/),
          sourceSectionSha256: hash,
          prerequisites: z.array(id).max(16),
          stages: z
            .array(
              z.strictObject({
                stage: z.enum(["PREDICT", "DEBUG", "BUILD", "DEFEND"]),
                id: z
                  .string()
                  .regex(
                    /^EJ41-0(?:57|58|59|60)-(?:PREDICT|DEBUG|BUILD|DEFEND)$/,
                  ),
              }),
            )
            .length(4),
        }),
      )
      .length(4),
    mappings: z.array(z.never()).length(0),
    blockers: z
      .array(
        z.strictObject({
          code: z.enum(["UNMAPPED_SKILL", "UNRESOLVED_EXTERNAL_PREREQUISITE"]),
          id,
        }),
      )
      .length(10),
  })
  .superRefine((p, ctx) => {
    const bad = () =>
      ctx.addIssue({ code: "custom", message: "Invalid preview identities" });
    const ids = new Set(p.skills.map((s) => s.id));
    if (ids.size !== 4 || new Set(p.skills.map((s) => s.sourceItem)).size !== 4)
      bad();
    for (const s of p.skills) {
      if (
        new Set(s.stages.map((t) => t.stage)).size !== 4 ||
        new Set(s.prerequisites).size !== s.prerequisites.length
      )
        bad();
      if (
        s.stages.some(
          (t) => t.id !== `EJ41-${s.sourceItem.slice(8)}-${t.stage}`,
        )
      )
        bad();
    }
    const expected = new Map<string, string>(
      p.skills.map((s) => [s.id, "UNMAPPED_SKILL"]),
    );
    p.skills
      .flatMap((s) => s.prerequisites)
      .filter((r) => !ids.has(r))
      .forEach((r) => expected.set(r, "UNRESOLVED_EXTERNAL_PREREQUISITE"));
    if (
      expected.size !== 10 ||
      new Set(p.blockers.map((b) => b.id)).size !== 10 ||
      p.blockers.some((b) => expected.get(b.id) !== b.code)
    )
      bad();
  });
export const CurriculumDataSchema = z.strictObject({
  v: z.literal(1),
  kind: z.literal("author-draft-curriculum-preview"),
  source: z.literal("bundled-author-metadata"),
  learnerBound: z.literal(false),
  preview: CurriculumPreviewSchema,
});
export const CurriculumErrorSchema = z.enum([
  "UNAVAILABLE",
  "RELEASE_DISABLED",
  "INVALID_CATALOG",
  "CANCELLED",
  "TIMEOUT",
  "PROTOCOL_ERROR",
  "START_FAILED",
]);
export const CurriculumResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ok: z.literal(true), result: CurriculumDataSchema }),
  z.strictObject({ ok: z.literal(false), code: CurriculumErrorSchema }),
]);
export const CurriculumRequestSchema = z.strictObject({
  v: z.literal(1),
  id: z.uuidv4(),
  method: z.literal("curriculum.preview.read"),
});
export const CurriculumWireSchema = z.discriminatedUnion("ok", [
  z.strictObject({
    v: z.literal(1),
    id: z.uuidv4(),
    ok: z.literal(true),
    result: CurriculumDataSchema,
  }),
  z.strictObject({
    v: z.literal(1),
    id: z.uuidv4(),
    ok: z.literal(false),
    code: z.literal("INVALID_CATALOG"),
  }),
]);
export type CurriculumPreview = z.infer<typeof CurriculumPreviewSchema>;
export type CurriculumResult = z.infer<typeof CurriculumResultSchema>;
export type CurriculumError = z.infer<typeof CurriculumErrorSchema>;
