import { z } from "zod";

export const MAX_FRAME_BYTES = 65_536;
export const DeckSchema = z.strictObject({
  id: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
  name: z.string().min(1).max(256),
  description: z.string().max(2048),
});
export const CatalogSchema = z.strictObject({
  source: z.literal("isolated-seed"),
  readOnly: z.literal(true),
  decks: z.array(DeckSchema).max(64),
});
export const ErrorCodeSchema = z.enum([
  "START_FAILED",
  "UNAVAILABLE",
  "READ_FAILED",
  "PROTOCOL_ERROR",
  "TIMEOUT",
  "CANCELLED",
  "BUSY",
]);
export type ErrorCode = z.infer<typeof ErrorCodeSchema>;
export const ResultSchema = z.discriminatedUnion("ok", [
  z.strictObject({ ok: z.literal(true), result: CatalogSchema }),
  z.strictObject({ ok: z.literal(false), code: ErrorCodeSchema }),
]);
export const RequestSchema = z.strictObject({
  v: z.literal(1),
  id: z.uuidv4(),
  method: z.literal("decks.list"),
});
export const WireResponseSchema = z.discriminatedUnion("ok", [
  z.strictObject({
    v: z.literal(1),
    id: z.uuidv4(),
    ok: z.literal(true),
    result: CatalogSchema,
  }),
  z.strictObject({
    v: z.literal(1),
    id: z.uuidv4(),
    ok: z.literal(false),
    code: z.literal("READ_FAILED"),
  }),
]);
export const ReadySchema = z.strictObject({
  v: z.literal(1),
  type: z.literal("ready"),
});
export type Catalog = z.infer<typeof CatalogSchema>;
export type Result = z.infer<typeof ResultSchema>;
export interface CodeFitBridge {
  listDecks(): Promise<Result>;
  cancelRead(): Promise<void>;
}
declare global {
  interface Window {
    codefit: CodeFitBridge;
  }
}
