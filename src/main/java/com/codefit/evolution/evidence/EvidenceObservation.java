package com.codefit.evolution.evidence;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import java.util.Objects;
import java.util.Set;

/** Immutable observation metadata, not copied answers/code/notes or a claim of mastery. */
public record EvidenceObservation(
        EvidenceSourceRef source,
        EvidenceTime time,
        Provenance provenance,
        Outcome outcome,
        Assistance assistance,
        Integer hintLevel,
        Exposure exposure,
        Set<Limitation> limitations,
        Availability availability,
        String resultCode,
        String originCode,
        String correlationKey,
        EvaluationReference evaluation,
        Change change,
        String previousEvidenceId,
        ChangeReason reason,
        Actor actor) {
    public static final int CONTRACT_VERSION = 1;

    public EvidenceObservation {
        Objects.requireNonNull(source);
        Objects.requireNonNull(time);
        Objects.requireNonNull(provenance);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(assistance);
        Objects.requireNonNull(exposure);
        Objects.requireNonNull(availability);
        Objects.requireNonNull(change);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(actor);
        limitations = Set.copyOf(limitations);
        EvaluationReference.requireTag(resultCode);
        EvaluationReference.requireTag(originCode);
        EvaluationReference.requireTag(correlationKey);
        if (hintLevel != null
                && (hintLevel < 0 || hintLevel > 100 || assistance != Assistance.HINT)) {
            throw new IllegalArgumentException(
                    "Hint level requires HINT assistance and a bounded level");
        }
        if (provenance == Provenance.AUTHENTICATED_EXTERNAL)
            throw new IllegalArgumentException(
                    "Authenticated external evidence is not implemented");
        if ((source.type() == SourceType.CHALLENGE_EVALUATION) != (evaluation != null)) {
            throw new IllegalArgumentException(
                    "Only durable challenge evaluations have evaluation references");
        }
        if (provenance == Provenance.LOCAL_CHECK && evaluation == null)
            throw new IllegalArgumentException("LOCAL_CHECK requires a durable evaluation");
        if (source.type() == SourceType.PROBLEM_ATTEMPT
                && provenance != Provenance.SELF_REPORTED
                && provenance != Provenance.LEGACY_UNSPECIFIED) {
            throw new IllegalArgumentException("Manual problem verdicts are not machine-verified");
        }
        if (source.type() == SourceType.ASSESSMENT_ATTEMPT
                && provenance != Provenance.LEGACY_UNSPECIFIED
                && provenance != Provenance.OBJECTIVE_REVIEW
                && provenance != Provenance.SELF_REPORTED) {
            throw new IllegalArgumentException(
                    "Assessment evidence requires persisted prospective grading facts or legacy uncertainty");
        }
        if (change == Change.ORIGINAL) {
            if (source.revision() != 1
                    || previousEvidenceId != null
                    || reason != ChangeReason.ORIGINAL_RECORD) {
                throw new IllegalArgumentException("Original observation must start at revision 1");
            }
        } else {
            if (source.revision() < 2
                    || previousEvidenceId == null
                    || !previousEvidenceId.matches("[0-9a-f]{64}")
                    || reason == ChangeReason.ORIGINAL_RECORD) {
                throw new IllegalArgumentException(
                        "Correction requires previous evidence, reason and next revision");
            }
        }
        if ((change == Change.RETRACTION) != (availability == Availability.RETRACTED)
                || (availability != Availability.AVAILABLE && outcome != Outcome.UNAVAILABLE)) {
            throw new IllegalArgumentException(
                    "Unavailable/retracted evidence cannot assert a performance outcome");
        }
    }

    public EvidenceObservation revise(int revision, String previous, ChangeReason why, Actor by) {
        return new EvidenceObservation(
                new EvidenceSourceRef(source.type(), source.recordId(), revision),
                time,
                provenance,
                outcome,
                assistance,
                hintLevel,
                exposure,
                limitations,
                availability,
                resultCode,
                originCode,
                correlationKey,
                evaluation,
                Change.SUPERSESSION,
                previous,
                why,
                by);
    }

    public EvidenceObservation retract(ChangeReason why, Actor by) {
        return new EvidenceObservation(
                new EvidenceSourceRef(source.type(), source.recordId(), source.revision() + 1),
                time,
                provenance,
                Outcome.UNAVAILABLE,
                assistance,
                hintLevel,
                exposure,
                limitations,
                Availability.RETRACTED,
                resultCode,
                originCode,
                correlationKey,
                evaluation,
                Change.RETRACTION,
                EvidenceIds.id(source),
                why,
                by);
    }

    /** Compare a persisted correction's factual payload with the current source projection. */
    public boolean sameFacts(EvidenceObservation other) {
        return time.equals(other.time)
                && provenance == other.provenance
                && outcome == other.outcome
                && assistance == other.assistance
                && Objects.equals(hintLevel, other.hintLevel)
                && exposure == other.exposure
                && limitations.equals(other.limitations)
                && availability == other.availability
                && resultCode.equals(other.resultCode)
                && originCode.equals(other.originCode)
                && correlationKey.equals(other.correlationKey)
                && Objects.equals(evaluation, other.evaluation);
    }
}
