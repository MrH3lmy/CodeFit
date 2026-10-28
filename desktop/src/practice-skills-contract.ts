import { z } from "zod";
const uuid = z.string().uuid(),
  ref = z.string().regex(/^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}$/),
  sha = z.string().regex(/^[0-9a-f]{64}$/);
const identity = { profileId: uuid, sessionId: uuid };
export const SkillsStateCommand = z.strictObject({
  method: z.literal("practice.skills.state"),
  ...identity,
});
export const SkillsReconcileCommand = z.strictObject({
  method: z.literal("practice.skills.reconcile"),
  ...identity,
  commandId: uuid,
  originSessionId: uuid,
});
export const SkillsStatusCommand = SkillsReconcileCommand.extend({
  method: z.literal("practice.skills.status"),
});
export const SkillsIntentSchema = z.strictObject({
  v: z.literal(1),
  profileId: uuid,
  commandId: uuid,
  originSessionId: uuid,
  operation: z.literal("pilot-ordinal-v1"),
});
export type SkillsIntent = z.infer<typeof SkillsIntentSchema>;
const skill = {
  id: ref,
  version: z.number().int().min(1).max(10000),
  name: z.string().min(1).max(160),
};
export const ReconciliationSchema = z
  .strictObject({
    commandId: uuid,
    originSessionId: uuid,
    state: z.enum(["PUBLISHED", "REJECTED", "INTERRUPTED"]),
    reason: z.enum([
      "SAVED",
      "SOURCE_CHANGED",
      "INPUT_UNAVAILABLE",
      "NOT_PUBLISHED",
    ]),
    asOf: z.string().datetime(),
    policy: z.literal("ordinal-v1"),
    inputSha: z.union([sha, z.literal("")]),
    skills: z
      .array(
        z.strictObject({
          ...skill,
          revision: z.number().int().positive().safe(),
          inputSha: sha,
          created: z.boolean(),
          epochId: ref,
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
          regressionCap: z.boolean(),
          independentFamilies: z.number().int().min(0).max(10000),
          exclusionCount: z.number().int().min(0).max(10000),
          behaviors: z.array(z.number().int().min(0).max(15)).max(16),
          reasons: z.array(z.string().min(1).max(2048)).max(64),
        }),
      )
      .max(4),
  })
  .refine((r) =>
    r.state === "PUBLISHED"
      ? r.skills.length === 4 &&
        new Set(r.skills.map((s) => s.id)).size === 4 &&
        r.inputSha.length === 64 &&
        r.reason === "SAVED"
      : r.skills.length === 0 &&
        r.inputSha === "" &&
        (r.state === "INTERRUPTED"
          ? r.reason === "NOT_PUBLISHED"
          : ["SOURCE_CHANGED", "INPUT_UNAVAILABLE"].includes(r.reason)),
  );
const fields = {
  v: z.literal(1),
  id: uuid,
  ok: z.literal(true),
  ...identity,
  graphId: z.literal("java-debugging-concurrency"),
  graphVersion: z.literal(1),
  policy: z.literal("ordinal-v1"),
  catalog: z.array(z.strictObject(skill)).length(4),
  reconciliation: ReconciliationSchema.nullable(),
};
export const SkillsStateResponse = z
  .strictObject({ ...fields, method: z.literal("practice.skills.state") })
  .refine(
    (r) =>
      new Set(r.catalog.map((s) => s.id)).size === 4 &&
      (!r.reconciliation ||
        r.reconciliation.skills.every((s) =>
          r.catalog.some((c) => c.id === s.id && c.version === s.version),
        )),
  );
export const SkillsCommandResponse = z
  .strictObject({
    ...fields,
    method: z.enum(["practice.skills.reconcile", "practice.skills.status"]),
    commandId: uuid,
    originSessionId: uuid,
  })
  .refine(
    (r) =>
      new Set(r.catalog.map((s) => s.id)).size === 4 &&
      (!r.reconciliation ||
        (r.reconciliation.commandId === r.commandId &&
          r.reconciliation.originSessionId === r.originSessionId &&
          r.reconciliation.skills.every((s) =>
            r.catalog.some((c) => c.id === s.id && c.version === s.version),
          ))),
  );
export type SkillsView =
  | z.infer<typeof SkillsStateResponse>
  | z.infer<typeof SkillsCommandResponse>;
