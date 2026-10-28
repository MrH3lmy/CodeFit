import type { SubmissionResult } from "./practice-contract";

/** Cache identity only, never proficiency or freshness. Revision comes from Java's
 * durable evaluation record, including corrections with unchanged outcomes. */
export function debriefIdentity(
  evaluation: SubmissionResult,
  identity: { profileId: string; sessionId: string },
): string {
  return JSON.stringify([
    identity.profileId,
    identity.sessionId,
    evaluation.commandId,
    evaluation.presentationId,
    evaluation.evaluationRevision,
    evaluation.withdrawn,
    evaluation.stage,
    evaluation.outcome,
  ]);
}
