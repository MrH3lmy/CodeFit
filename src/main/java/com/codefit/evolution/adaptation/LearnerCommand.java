package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.quest.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One explicit choice, with a stable identity reused after a lost response. UTC dates are instants.
 */
public record LearnerCommand(
        String id,
        CommandKind kind,
        String decisionId,
        CatalogRef challenge,
        SkillRef goal,
        Integer budgetMinutes,
        Track track,
        Instant deferUntil) {
    public LearnerCommand {
        ChallengePresentation.identity(id);
        Objects.requireNonNull(kind);
        if (decisionId != null) ChallengePresentation.identity(decisionId);
        boolean candidate =
                switch (kind) {
                    case ACCEPT, DECLINE, DEFER, CHOOSE_ALTERNATIVE, EXPLORE -> true;
                    default -> false;
                };
        if (candidate && (decisionId == null || (challenge == null && kind != CommandKind.ACCEPT)))
            throw new IllegalArgumentException("Decision and exact candidate required");
        if (kind == CommandKind.RESUME_CANDIDATE && challenge == null)
            throw new IllegalArgumentException("Candidate required");
        if (kind == CommandKind.DEFER && deferUntil == null)
            throw new IllegalArgumentException("UTC defer time required");
        if (kind == CommandKind.CHANGE_BUDGET
                && (budgetMinutes == null || budgetMinutes < 1 || budgetMinutes > 180))
            throw new IllegalArgumentException("Budget 1..180 required");
        if (kind == CommandKind.SWITCH_TRACK && track == null)
            throw new IllegalArgumentException("Track required");
        // Reject irrelevant fields: replay identity must cover exactly what the command means.
        if ((!candidate && decisionId != null)
                || (!candidate && kind != CommandKind.RESUME_CANDIDATE && challenge != null)
                || (kind != CommandKind.CHANGE_GOAL && goal != null)
                || (kind != CommandKind.CHANGE_BUDGET && budgetMinutes != null)
                || (kind != CommandKind.SWITCH_TRACK && track != null)
                || (kind != CommandKind.DEFER && deferUntil != null))
            throw new IllegalArgumentException("Unexpected command field");
    }

    public static LearnerCommand simple(String id, CommandKind kind) {
        return new LearnerCommand(id, kind, null, null, null, null, null, null);
    }
}
