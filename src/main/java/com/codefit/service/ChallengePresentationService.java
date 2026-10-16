package com.codefit.service;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.ChallengePresentation.*;
import com.codefit.model.*;
import com.codefit.repository.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Fail-closed #216 presentation boundary. Commit exposure before rendering any content. Receipts
 * are not grades, submissions or evidence. Generic problem routes cannot open these bound Problems.
 * Local assets are not an anti-cheat sandbox; users can inspect their own installation.
 */
public final class ChallengePresentationService {
    public record View(
            ChallengePresentation receipt,
            QuestCatalogRepository.Backing backing,
            String prompt,
            JavaSolutionDraft draft,
            List<ChallengeRubric.Check> publicChecks,
            ExposureContext currentContext) {}

    public record SubmissionContext(
            ChallengePresentation receipt,
            ChallengeSpec challenge,
            QuestCatalogRepository.Backing backing,
            ExposureContext currentContext) {}

    private final QuestCatalogRepository catalog;
    private final ChallengeExposureRepository exposures = new ChallengeExposureRepository();
    private final BundledAssets assets;
    private final Clock clock;

    public ChallengePresentationService() {
        this(new QuestCatalogRepository(), new BundledAssets(), Clock.systemUTC());
    }

    public ChallengePresentationService(
            QuestCatalogRepository catalog, BundledAssets assets, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog);
        this.assets = Objects.requireNonNull(assets);
        this.clock = Objects.requireNonNull(clock);
    }

    public View present(Request request) {
        String id = catalog.transaction(c -> present(c, request));
        // Rendering can fail here, but exposure is already durable and will never be rolled back.
        return resume(id);
    }

    /** Caller-owned writer boundary for #215: eligibility and presentation commit together.
     * Returns only a receipt ID. Render via resume AFTER the outer transaction commits. */
    public String present(Connection c, Request request) throws SQLException {
        var spec =
                catalog.findChallenge(c, request.challenge())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown challenge"));
        assets.validate(spec);
        catalog.verifyBinding(c, spec);
        var receipt = exposures.append(c, request, clock.instant());
        var backing = catalog.backing(c, spec.ref());
        if (backing.problemId() == null) return receipt.id();
        long problem = backing.problemId();
        var sessions = new ProblemSolvingSessionRepository();
        if (sessions.findByProblemId(c, problem).isEmpty()) {
            var now =
                    LocalDateTime.ofInstant(
                            receipt.presentedAt(), ZoneOffset.UTC);
            sessions.save(
                    c,
                    new ProblemSolvingSession(
                            0,
                            problem,
                            SolvingPhase.READING,
                            0,
                            0,
                            0,
                            0,
                            null,
                            true,
                            true,
                            now,
                            now));
        }
        var drafts = new JavaSolutionDraftRepository();
        if (drafts.findByProblemId(c, problem).isEmpty())
            drafts.save(
                    c,
                    new JavaSolutionDraft(
                            0,
                            problem,
                            "Main",
                            asset(spec, AssetKind.STARTER),
                            "",
                            "",
                            null));
        return receipt.id();
    }

    public View resume(String presentationId) {
        var context = contextForSubmission(presentationId);
        var spec = context.challenge();
        assets.validate(spec);
        return new View(
                context.receipt(),
                context.backing(),
                asset(spec, AssetKind.PROMPT),
                context.backing().problemId() == null
                        ? null
                        : new JavaSolutionDraftRepository()
                                .findByProblemId(context.backing().problemId())
                                .orElseThrow(),
                spec.rubric().checks().stream()
                        .filter(c -> c.visibility() == Visibility.PUBLIC)
                        .toList(),
                context.currentContext());
    }

    /**
     * #214 must freeze this metadata with the deliberate submission and exact draft digest. No
     * evaluator here.
     */
    public SubmissionContext contextForSubmission(String id) {
        return catalog.transaction(c -> contextForSubmission(c, id));
    }

    /** #214 may capture this on the SAME writer transaction as its prepared submission record. */
    public SubmissionContext contextForSubmission(Connection c, String id) throws SQLException {
        catalog.lock(c);
        var receipt =
                exposures
                        .find(c, id)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown presentation"));
        var spec = catalog.findChallenge(c, receipt.challenge()).orElseThrow();
        catalog.verifyBinding(c, spec);
        return new SubmissionContext(
                receipt, spec, catalog.backing(c, spec.ref()), exposures.context(c, spec));
    }

    public String revealHint(String presentationId, String eventId, int level) {
        var context = contextForSubmission(presentationId);
        var hint =
                context.challenge().hints().stream()
                        .filter(h -> h.level() == level)
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown hint"));
        String text = assets.read(context.challenge().asset(hint.assetId()));
        event(presentationId, eventId, ExposureAction.HINT, level);
        return text;
    }

    public String revealSolution(String presentationId, String eventId) {
        var context = contextForSubmission(presentationId);
        String text = asset(context.challenge(), AssetKind.REFERENCE);
        event(presentationId, eventId, ExposureAction.SOLUTION, 0);
        return text;
    }

    public void skip(String presentationId, String eventId) {
        event(presentationId, eventId, ExposureAction.SKIP, 0);
    }

    public void declareFamiliar(String presentationId, String eventId) {
        event(presentationId, eventId, ExposureAction.FAMILIARITY_DECLARED, 0);
    }

    public List<ChallengePresentation> history(String family) {
        CatalogRef.id(family);
        return catalog.transaction(c -> exposures.history(c, family));
    }

    public void saveDraft(String presentationId, String source) {
        if (source == null || source.length() > 200000)
            throw new IllegalArgumentException("Bounded source required");
        var context = contextForSubmission(presentationId);
        if (context.backing().problemId() == null)
            throw new IllegalArgumentException("Keyed diagnostics do not use Java drafts");
        var drafts = new JavaSolutionDraftRepository();
        var draft = drafts.findByProblemId(context.backing().problemId()).orElseThrow();
        draft.setSourceCode(source);
        drafts.update(draft);
    }

    private void event(String presentation, String event, ExposureAction action, int level) {
        catalog.transaction(
                c -> {
                    exposures.event(c, event, presentation, action, level, clock.instant());
                    return null;
                });
    }

    private String asset(ChallengeSpec spec, AssetKind kind) {
        return assets.read(
                spec.assets().stream().filter(a -> a.kind() == kind).findFirst().orElseThrow());
    }
}
