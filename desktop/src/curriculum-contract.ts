import { z } from "zod";

export const CURRICULUM_MAX_BYTES = 32768;
export const PUBLIC_PREVIEW_SHA =
  "95ff42f24a88ac85400a4cda43daf9e0caabab8e4e324fa21c992f78a2ecb874";
export const COVERAGE_LEDGER_SHA =
  "0466d16b2ec5809383b7d0bb40d068430bcd3890e06115f5be2cfffe46a4057e";
export const PUBLIC_COVERAGE_SHA =
  "9609e1794c79681eb5b0558f2b0c8d965c732434bda841c2e3c9172902a2830d";
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
const units = z.number().int().min(1).max(504);
const unknownCount = z.strictObject({
  status: z.literal("UNKNOWN"),
  count: z.null(),
  denominator: units,
});
const zeroReported = z.strictObject({
  status: z.literal("ZERO_REPORTED"),
  count: z.literal(0),
  denominator: units,
});
export const CoverageSchema = z
  .strictObject({
    v: z.literal(1),
    schema: z.literal("codefit-knowledge-coverage/1"),
    ledgerSchema: z.literal("codefit-knowledge-coverage-ledger/1"),
    ledgerVersion: z.literal("1.0.0"),
    ledgerSha256: z.literal(COVERAGE_LEDGER_SHA),
    sourcePath: z.literal(
      "docs/knowledge-compiler/hunter-pilot-v0.41/coverage-ledger-input.json",
    ),
    localReviewOnly: z.literal(true),
    productionImportSupported: z.literal(false),
    provenance: z.strictObject({
      observedAt: z.literal("2026-10-23"),
      sourceVersion: z.literal("v0.41"),
      source: z.literal(
        "v0.41 delivery report, as attributed in CodeFit issue #298",
      ),
      verification: z.literal("SOURCE_ATTRIBUTED_NOT_INDEPENDENTLY_VERIFIED"),
      privateSourceBodies: z.literal("NOT_INDEPENDENTLY_VERIFIED"),
      previousCheckpoint: z.literal("NOT_AVAILABLE"),
      limitations: z.array(text).length(3),
    }),
    books: z.array(z.strictObject({
      id: z.enum([
        "ocp-java-se-17-guide", "effective-java", "100-java-mistakes",
        "java-coding-problems", "complete-coding-interview-guide-java",
      ]),
      title: z.enum([
        "OCP Java SE 17 Guide", "Effective Java", "100 Java Mistakes",
        "Java Coding Problems", "Complete Coding Interview Guide in Java",
      ]),
      inventory: z.strictObject({
        status: z.literal("REPORTED_NOT_ENUMERATED"),
        count: units,
        denominator: z.null(),
      }),
      preliminaryMapped: unknownCount,
      authorDraft: z.discriminatedUnion("status", [
        unknownCount,
        z.strictObject({
          status: z.literal("REPORTED_FIRST_PASS_NOT_REVIEWED"),
          count: z.literal(90),
          denominator: z.literal(90),
        }),
      ]),
      independentReview: zeroReported,
      runtimeLearning: zeroReported,
    })).length(5),
    reportedClaims: z.strictObject({
      structuralUnits: z.literal(504),
      preliminaryMappedUnits: z.literal(157),
      reportedUnmappedUnits: z.literal(347),
      effectiveJavaFirstPassAuthorNotes: z.strictObject({
        count: z.literal(90),
        denominator: z.literal(90),
      }),
      missionBlueprintSlots: z.literal(419),
      deepOverlaySlots: z.literal(360),
      revisionEvents: z.strictObject({
        count: z.literal(424),
        additiveToMissionBlueprintSlots: z.literal(false),
        records: z.null(),
      }),
      independentApprovals: z.literal(0),
      productionGraders: z.literal(0),
      hunterProductionImports: z.literal(0),
    }),
    learnerValidated: z.strictObject({
      status: z.literal("UNKNOWN"),
      count: z.null(),
      denominator: z.null(),
    }),
    followupGaps: z.array(z.enum([
      "UNIT_IDS_UNAVAILABLE", "PER_BOOK_PRELIMINARY_MAPPING_UNKNOWN",
      "FIRST_PASS_NOTES_NOT_INDEPENDENT_APPROVAL",
      "INDEPENDENT_REVIEW_EVIDENCE_UNAVAILABLE",
      "REVISION_EVENT_RECORDS_UNAVAILABLE", "RUNTIME_LEARNING_NOT_ESTABLISHED",
      "LEARNER_VALIDATION_UNKNOWN",
    ])).length(7),
  })
  .superRefine((coverage, ctx) => {
    const bad = () => ctx.addIssue({
      code: "custom", message: "Invalid coverage identities",
    });
    if (new Set(coverage.books.map((b) => b.id)).size !== 5 ||
        new Set(coverage.followupGaps).size !== 7) bad();
    if (coverage.books.reduce((sum, b) => sum + b.inventory.count, 0) !==
        coverage.reportedClaims.structuralUnits) bad();
    for (const b of coverage.books) {
      const expected = {
        "ocp-java-se-17-guide": ["OCP Java SE 17 Guide", 26],
        "effective-java": ["Effective Java", 90],
        "100-java-mistakes": ["100 Java Mistakes", 100],
        "java-coding-problems": ["Java Coding Problems", 269],
        "complete-coding-interview-guide-java": ["Complete Coding Interview Guide in Java", 19],
      } as const;
      if (b.title !== expected[b.id][0] ||
          b.inventory.count !== expected[b.id][1]) bad();
      if ([b.preliminaryMapped, b.authorDraft, b.independentReview, b.runtimeLearning]
          .some((c) => c.denominator !== b.inventory.count)) bad();
      if ((b.id === "effective-java") !==
          (b.authorDraft.status === "REPORTED_FIRST_PASS_NOT_REVIEWED")) bad();
    }
  });
export const CurriculumDataSchema = z.strictObject({
  v: z.literal(2),
  kind: z.literal("author-draft-curriculum-preview"),
  source: z.literal("bundled-author-metadata"),
  learnerBound: z.literal(false),
  preview: CurriculumPreviewSchema,
  coverage: CoverageSchema,
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
export type Coverage = z.infer<typeof CoverageSchema>;
export type CurriculumResult = z.infer<typeof CurriculumResultSchema>;
export type CurriculumError = z.infer<typeof CurriculumErrorSchema>;
