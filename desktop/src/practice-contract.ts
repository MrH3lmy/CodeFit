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
  "STALE_REVISION",
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
export const SubmissionInputSchema = z.strictObject({
  presentationId: presentation,
  expectedRevision: revision,
  assistance: z.enum(["UNKNOWN", "NONE_DECLARED", "DECLARED"]),
});
export const SubmissionIntentSchema = SubmissionInputSchema.extend({
  v: z.literal(1),
  profileId: uuid,
  commandId: uuid,
});
export const SubmissionResultSchema = z
  .strictObject({
    commandId: uuid,
    presentationId: presentation,
    draftRevision: revision,
    evaluationRevision: z.number().int().min(1).max(2147483647).nullable(),
    assistance: SubmissionInputSchema.shape.assistance,
    challengeId: ref,
    challengeVersion: version,
    submittedAt: z.string().datetime(),
    artifactSha256: z.string().regex(/^[0-9a-f]{64}$/),
    active: z.boolean(),
    cancellationRequested: z.boolean(),
    withdrawn: z.boolean(),
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
    checks: z
      .array(
        z.strictObject({
          id: ref,
          status: z.enum([
            "NOT_RUN",
            "PASSED",
            "FAILED",
            "RUNTIME_ERROR",
            "RESOURCE_EXHAUSTED",
            "TIMED_OUT",
            "CANCELLED",
            "INCONCLUSIVE",
            "UNSUPPORTED",
            "EVALUATOR_ERROR",
            "UNGRADED",
          ]),
          feedback: z.string().max(2048),
        }),
      )
      .max(64),
  })
  .refine((r) =>
    r.stage === "FINALIZED"
      ? r.evaluationRevision !== null
      : r.evaluationRevision === null,
  )
  .refine((r) =>
    r.stage === "FINALIZED" || r.stage === "EVALUATED"
      ? r.outcome !== null
      : r.outcome === null && r.checks.length === 0,
  );
export const DebriefInputSchema = z.strictObject({
  commandId: uuid,
  presentationId: presentation,
});
const code = z.string().regex(/^[A-Z][A-Z0-9_]{0,95}$/);
const sha = z.string().regex(/^[0-9a-f]{64}$/);
const band = z.enum([
  "UNKNOWN",
  "DEVELOPING",
  "INDEPENDENT",
  "TRANSFER_SUPPORTED",
  "RETENTION_SUPPORTED",
]);
const interpretation = z
  .strictObject({
    status: z.enum(["missing", "unavailable", "historical"]),
    reason: code.nullable(),
    revision: z
      .number()
      .int()
      .positive()
      .max(Number.MAX_SAFE_INTEGER)
      .nullable(),
    asOf: z.string().datetime().nullable(),
    policyVersion: ref.nullable(),
    band: band.nullable(),
    support: z.enum(["INSUFFICIENT", "LIMITED", "DIVERSE"]).nullable(),
    freshness: z.enum(["NO_OBSERVATION", "CURRENT", "STALE"]).nullable(),
    contradicted: z.boolean().nullable(),
    exclusionCount: z.number().int().min(0).max(10000),
    currentSupport: z.literal("NOT_EVALUATED"),
    supportedBehaviors: z.array(z.number().int().min(0).max(15)).max(16),
    reasons: z.array(code).max(128),
    warnings: z.array(code).max(32),
  })
  .refine((i) =>
    i.status === "historical"
      ? i.revision !== null &&
        i.asOf !== null &&
        i.band !== null &&
        i.support !== null &&
        i.freshness !== null
      : i.revision === null && i.asOf === null && i.band === null,
  );
export const DebriefSchema = z
  .strictObject({
    v: z.literal(1),
    readOnly: z.literal(true),
    readAt: z.string().datetime(),
    currentEligibility: z.literal("NOT_EVALUATED"),
    nextAction: z.literal("EXPLORATORY_PRACTICE"),
    evaluation: SubmissionResultSchema.refine(
      (r) => r.stage === "FINALIZED" && !r.active,
    ),
    questId: ref,
    questVersion: version,
    graphId: ref,
    graphVersion: version,
    rubricId: ref,
    rubricVersion: version,
    evaluationRevision: z.number().int().min(1).max(64),
    corrected: z.boolean(),
    familyId: ref,
    variantId: ref,
    cohort: z.literal("PRACTICE"),
    familiarity: z.enum([
      "UNKNOWN",
      "DECLARED_UNFAMILIAR",
      "DECLARED_FAMILIAR",
    ]),
    solutionRevealed: z.boolean(),
    priorFamilySubmissions: z
      .number()
      .int()
      .min(0)
      .max(Number.MAX_SAFE_INTEGER),
    limitations: z.string().max(4096),
    checks: z
      .array(
        z.strictObject({
          id: ref,
          kind: z.enum([
            "DETERMINISTIC_IO",
            "KEYED_DIAGNOSTIC",
            "CONTROLLED_TRACE",
            "OBSERVED_CONCURRENCY",
            "TERMINATION",
            "REFLECTION",
          ]),
          required: z.boolean(),
          status: SubmissionResultSchema.shape.checks.element.shape.status,
          feedback: z.string().max(2048),
          limitations: z.string().max(4096),
          targets: z
            .array(
              z.strictObject({
                skillId: ref,
                skillVersion: version,
                behavior: z.number().int().min(0).max(15),
              }),
            )
            .max(16),
        }),
      )
      .max(64),
    evidence: z
      .strictObject({
        status: z.enum([
          "RECORDED",
          "LIMIT_EXCEEDED",
          "SOURCE_UNAVAILABLE",
          "SUPERSEDED_OR_INVALID",
        ]),
        id: sha.nullable(),
        revision: z.number().int().min(1).max(64).nullable(),
        previousId: sha.nullable(),
        recordedAt: z.string().datetime().nullable(),
        provenance: z.literal("LOCAL_CHECK").nullable(),
        outcome: z.enum(["PASS", "FAIL", "PARTIAL", "UNAVAILABLE"]).nullable(),
        availability: z
          .enum([
            "AVAILABLE",
            "SOURCE_UNAVAILABLE",
            "RETRACTED",
            "UNSUPPORTED_VERSION",
          ])
          .nullable(),
        assistance: z
          .enum([
            "UNKNOWN",
            "HINT",
            "NO_IN_APP_HINT_RECORDED",
            "NONE_OBSERVED",
            "EXPLANATION_OR_SOLUTION",
            "DECLARED_EXTERNAL",
          ])
          .nullable(),
        exposure: z
          .enum([
            "UNKNOWN",
            "FAMILIAR_RECALL",
            "REPEAT",
            "RELATED_VARIANT",
            "FIRST_PRESENTATION",
            "HELD_OUT_TRANSFER",
            "DELAYED_RETENTION",
          ])
          .nullable(),
        change: z.enum(["ORIGINAL", "SUPERSESSION", "RETRACTION"]).nullable(),
        limitations: z.array(code).max(32),
        mappingId: ref.nullable(),
        mappingVersion: version.nullable(),
        mappingStatus: z.enum(["EXACT", "UNAVAILABLE", "SUPERSEDED"]),
      })
      .refine((e) =>
        e.status === "RECORDED"
          ? e.id !== null &&
            e.revision !== null &&
            e.recordedAt !== null &&
            e.outcome !== null
          : e.id === null && e.revision === null,
      ),
    skillStatus: z.enum(["available", "GRAPH_UNAVAILABLE"]),
    skills: z
      .array(
        z.strictObject({
          id: ref,
          version,
          name: z.string().min(1).max(160),
          behaviors: z.array(z.string().min(1).max(1024)).max(16),
          interpretation,
        }),
      )
      .max(16),
  })
  .refine(
    (d) =>
      d.evaluation.evaluationRevision === d.evaluationRevision &&
      d.corrected === d.evaluationRevision > 1 &&
      d.checks.length === d.evaluation.checks.length &&
      new Set(d.checks.map((c) => c.id)).size === d.checks.length &&
      d.checks.every((c) =>
        d.evaluation.checks.some(
          (e) =>
            e.id === c.id && e.status === c.status && e.feedback === c.feedback,
        ),
      ) &&
      (d.skillStatus === "available" || d.skills.length === 0) &&
      d.skills.every(
        (s) =>
          s.interpretation.asOf === null ||
          Date.parse(s.interpretation.asOf) <= Date.parse(d.readAt),
      ),
  );
export type LearningDebrief = z.infer<typeof DebriefSchema>;
export type DebriefInput = z.infer<typeof DebriefInputSchema>;
const debriefRead = z.strictObject({
  method: z.literal("practice.submission.debrief"),
  ...identity,
  ...DebriefInputSchema.shape,
});
const submissionPrepare = z.strictObject({
  method: z.literal("practice.submission.prepare"),
  ...identity,
  commandId: uuid,
  ...SubmissionInputSchema.shape,
});
const submissionStatus = z.strictObject({
  method: z.literal("practice.submission.status"),
  ...identity,
  commandId: uuid,
  presentationId: presentation,
});
const submissionResume = submissionStatus.extend({
  method: z.literal("practice.submission.resume"),
});
const submissionCancel = submissionStatus.extend({
  method: z.literal("practice.submission.cancel"),
});
const submissions = z.strictObject({
  method: z.literal("practice.submissions"),
  ...identity,
  presentationId: presentation,
});
export type SubmissionInput = z.infer<typeof SubmissionInputSchema>;
export type SubmissionIntent = z.infer<typeof SubmissionIntentSchema>;
export type SubmissionResult = z.infer<typeof SubmissionResultSchema>;
export const SubmissionActionSchema = z.strictObject({
  action: z.enum(["status", "resume", "cancel"]),
  commandId: uuid,
  presentationId: presentation,
});
export type SubmissionAction = z.infer<typeof SubmissionActionSchema>;
export const PracticeCommandSchema = z.discriminatedUnion("method", [
  list,
  status,
  open,
  presentations,
  resume,
  draftRead,
  draftSave,
  draftStatus,
  submissionPrepare,
  submissionStatus,
  submissionResume,
  submissionCancel,
  submissions,
  debriefRead,
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
  submissionPrepare.extend(envelope),
  submissionStatus.extend(envelope),
  submissionResume.extend(envelope),
  submissionCancel.extend(envelope),
  submissions.extend(envelope),
  debriefRead.extend(envelope),
]);
const success = { ...envelope, ok: z.literal(true), ...identity };
export const PracticeWireSchema = z.union([
  z.strictObject({
    ...success,
    method: z.literal("practice.submission.debrief"),
    ...DebriefInputSchema.shape,
    debrief: DebriefSchema,
  }),
  z.strictObject({
    ...success,
    method: z.literal("practice.submissions"),
    presentationId: presentation,
    submissions: z.array(SubmissionResultSchema).max(32),
  }),
  z.strictObject({
    ...success,
    method: z.enum([
      "practice.submission.prepare",
      "practice.submission.status",
      "practice.submission.resume",
      "practice.submission.cancel",
    ]),
    commandId: uuid,
    presentationId: presentation,
    evaluation: SubmissionResultSchema.nullable(),
  }),
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
      pendingSubmission: SubmissionIntent | null;
    }
  | { ok: false; code: PracticeError };
