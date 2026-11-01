import { z } from "zod";
const uuid = z.string().uuid(),
  id = z
    .string()
    .min(1)
    .max(128)
    .regex(/^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$/),
  version = z.number().int().min(1).max(2147483647),
  revision = z.number().int().min(0).max(9007199254740990);
export const GraphRefSchema = z.strictObject({
  graphId: id,
  graphVersion: version,
});
const target = { graphId: id, graphVersion: version };
const query = {
  graphId: z.union([id, z.literal("")]),
  graphVersion: z.number().int().min(0).max(2147483647),
};
const identity = { profileId: uuid, sessionId: uuid };
export const GraphReadCommand = z
  .strictObject({
    method: z.literal("practice.graphs.read"),
    ...identity,
    ...query,
  })
  .refine((r) => (r.graphId === "") === (r.graphVersion === 0));
export const GraphSelectCommand = z.strictObject({
  method: z.literal("practice.graphs.select"),
  ...identity,
  ...target,
  selectionRevision: revision,
  commandId: uuid,
  originSessionId: uuid,
});
export const GraphStatusCommand = GraphSelectCommand.extend({
  method: z.literal("practice.graphs.status"),
});
export const GraphIntentSchema = z.strictObject({
  v: z.literal(1),
  operation: z.literal("graph-selection-v1"),
  profileId: uuid,
  originSessionId: uuid,
  commandId: uuid,
  ...target,
  selectionRevision: revision,
});
export type GraphIntent = z.infer<typeof GraphIntentSchema>;
export const GraphActionSchema = z.discriminatedUnion("kind", [
  z
    .strictObject({ kind: z.literal("read"), ...query })
    .refine((r) => (r.graphId === "") === (r.graphVersion === 0)),
  z.strictObject({
    kind: z.literal("select"),
    ...target,
    selectionRevision: revision,
  }),
  z.strictObject({ kind: z.literal("status") }),
  z.strictObject({ kind: z.literal("retry") }),
]);
export type GraphAction = z.infer<typeof GraphActionSchema>;
const selectionStatus = z.enum([
  "ELIGIBLE",
  "GRAPH_MISSING",
  "GRAPH_RETIRED",
  "RETIRED_SKILL",
  "DOMAIN_UNSUPPORTED",
]);
export const GraphReceiptSchema = z
  .strictObject({
    commandId: uuid,
    originSessionId: uuid,
    target: GraphRefSchema,
    expectedRevision: revision,
    state: z.enum(["SELECTED", "UNCHANGED", "REJECTED"]),
    reason: z.enum([
      "SAVED",
      "ALREADY_SELECTED",
      "SELECTION_CONFLICT",
      "GRAPH_MISSING",
      "GRAPH_RETIRED",
      "RETIRED_SKILL",
      "DOMAIN_UNSUPPORTED",
    ]),
    selected: GraphRefSchema,
    revision,
    recordedAt: z.string().datetime(),
  })
  .refine((r) =>
    r.state === "SELECTED"
      ? r.reason === "SAVED" &&
        r.revision === r.expectedRevision + 1 &&
        r.selected.graphId === r.target.graphId &&
        r.selected.graphVersion === r.target.graphVersion
      : r.state === "UNCHANGED"
        ? r.reason === "ALREADY_SELECTED" &&
          r.revision === r.expectedRevision &&
          r.selected.graphId === r.target.graphId &&
          r.selected.graphVersion === r.target.graphVersion
        : !["SAVED", "ALREADY_SELECTED"].includes(r.reason),
  );
const summary = GraphRefSchema.extend({
  domain: id,
  skillCount: z.number().int().min(1).max(64),
  lifecycle: z.enum(["ACTIVE", "RETIRED"]),
  lifecycleRevision: revision,
  replacement: GraphRefSchema.nullable(),
  selectionStatus,
  mappingStatus: z.enum(["NONE", "EXACT_PUBLISHED", "INCOMPATIBLE"]),
  mappingCount: z.number().int().min(0).max(64),
  estimator: z.enum([
    "SUPPORTED_PILOT",
    "UNSUPPORTED_GRAPH",
    "MAPPING_UNAVAILABLE",
    "LIFECYCLE_UNAVAILABLE",
  ]),
  contentReview: z.literal("NOT_RECORDED"),
  practice: z.enum(["SUPPORTED_PILOT", "UNAVAILABLE"]),
}).refine((s) =>
  s.practice === "SUPPORTED_PILOT"
    ? s.estimator === "SUPPORTED_PILOT" &&
      s.selectionStatus === "ELIGIBLE" &&
      s.graphId === "java-debugging-concurrency" &&
      s.graphVersion === 1
    : s.estimator !== "SUPPORTED_PILOT",
);
const detail = z
  .strictObject({
    skills: z
      .array(
        z.strictObject({
          id,
          version,
          domain: id,
          name: z.string().min(1).max(160),
          description: z.string().min(1).max(2048),
          lifecycle: z.enum(["ACTIVE", "RETIRED"]),
          behaviors: z.array(z.string().min(1).max(512)).min(1).max(8),
        }),
      )
      .min(1)
      .max(64),
    edges: z
      .array(
        z.strictObject({
          from: id,
          fromVersion: version,
          to: id,
          toVersion: version,
        }),
      )
      .max(256),
  })
  .refine(
    (d) =>
      new Set(d.skills.map((s) => s.id)).size === d.skills.length &&
      new Set(d.edges.map((e) => e.from + ":" + e.to)).size ===
        d.edges.length &&
      d.edges.every(
        (e) =>
          e.from !== e.to &&
          d.skills.some(
            (s) => s.id === e.from && s.version === e.fromVersion,
          ) &&
          d.skills.some((s) => s.id === e.to && s.version === e.toVersion),
      ),
  );
export const GraphViewSchema = z
  .strictObject({
    capturedAt: z.string().datetime(),
    track: z.literal("engineering-practice"),
    selected: GraphRefSchema,
    revision,
    selectionStatus,
    graphs: z.array(summary).max(32),
    inspected: GraphRefSchema,
    detail: detail.nullable(),
  })
  .refine(
    (v) =>
      new Set(v.graphs.map((g) => g.graphId + ":" + g.graphVersion)).size ===
        v.graphs.length &&
      (v.detail === null ||
        v.graphs.some(
          (g) =>
            g.graphId === v.inspected.graphId &&
            g.graphVersion === v.inspected.graphVersion &&
            g.skillCount === v.detail!.skills.length,
        )),
  );
export const GraphResponse = z
  .strictObject({
    v: z.literal(1),
    id: uuid,
    ok: z.literal(true),
    ...identity,
    method: z.enum([
      "practice.graphs.read",
      "practice.graphs.select",
      "practice.graphs.status",
    ]),
    commandId: z.union([uuid, z.literal("")]),
    originSessionId: z.union([uuid, z.literal("")]),
    pending: GraphIntentSchema.nullable(),
    view: GraphViewSchema.nullable(),
    receipt: GraphReceiptSchema.nullable(),
  })
  .refine(
    (r) =>
      (!r.pending || r.pending.profileId === r.profileId) &&
      (r.method === "practice.graphs.read"
        ? r.commandId === "" &&
          r.originSessionId === "" &&
          r.view !== null &&
          r.receipt === null
        : r.commandId !== "" &&
          r.originSessionId !== "" &&
          r.view === null &&
          (r.method === "practice.graphs.status" || r.receipt !== null) &&
          (!r.receipt ||
            (r.receipt.commandId === r.commandId &&
              r.receipt.originSessionId === r.originSessionId))),
  );
export type GraphView = z.infer<typeof GraphViewSchema>;
export type GraphReply = z.infer<typeof GraphResponse>;
export type GraphReceipt = z.infer<typeof GraphReceiptSchema>;
