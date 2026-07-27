package com.codefit.service;

import com.codefit.model.GuidanceSource;
import com.codefit.model.HintLevel;
import com.codefit.model.ProblemGuidance;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.SolvedWith;
import com.codefit.repository.ProblemGuidanceRepository;
import com.codefit.repository.ProblemSolvingSessionRepository;

import java.util.List;
import java.util.Optional;

/**
 * Owns the progressive hint ladder (#162): revealing one {@link HintLevel} at a time, tracking the
 * highest level opened per attempt (on the current {@link ProblemSolvingSession}, so it resets for
 * free the next time a new attempt starts — see that model's class docs), and authoring/editing the
 * underlying {@link ProblemGuidance} content behind it.
 *
 * <p>Never fabricates content: a level with no authored text reveals as "no content yet" (see
 * {@link HintReveal#hasContent()}) rather than inventing something plausible-sounding, and no method
 * here ever bundles or paraphrases third-party editorial text — {@link GuidanceSource} exists so the
 * product always knows whose words a given row's text actually is.
 */
public class ProblemGuidanceService {

    private final ProblemGuidanceRepository guidanceRepository;
    private final ProblemSolvingSessionRepository sessionRepository;

    public ProblemGuidanceService() {
        this(new ProblemGuidanceRepository(), new ProblemSolvingSessionRepository());
    }

    public ProblemGuidanceService(ProblemGuidanceRepository guidanceRepository, ProblemSolvingSessionRepository sessionRepository) {
        this.guidanceRepository = guidanceRepository;
        this.sessionRepository = sessionRepository;
    }

    public Optional<ProblemGuidance> getGuidance(long problemId) {
        return guidanceRepository.findByProblemId(problemId);
    }

    public List<String> getPrerequisites(long problemId) {
        return getGuidance(problemId).map(g -> AcceptedAnswerCodec.decode(g.getPrerequisitesEncoded())).orElse(List.of());
    }

    public List<String> getReferenceLinks(long problemId) {
        return getGuidance(problemId).map(g -> AcceptedAnswerCodec.decode(g.getReferenceLinksEncoded())).orElse(List.of());
    }

    /**
     * Creates or updates the problem's one guidance row in place — this is the "allow editing/
     * improving local guidance" requirement: there is no versioning or append-only history, editing
     * simply overwrites the previous text for whichever fields are provided. {@code null} for any
     * text field leaves that specific level blank, never an empty string standing in for "no content".
     */
    public ProblemGuidance saveGuidance(long problemId, GuidanceSource source, String clarifyText, String observationText,
                                        String approachText, String explanationText, List<String> prerequisites,
                                        List<String> referenceLinks) {
        String prerequisitesEncoded = encodeOrNull(prerequisites);
        String referenceLinksEncoded = encodeOrNull(referenceLinks);
        Optional<ProblemGuidance> existing = guidanceRepository.findByProblemId(problemId);
        if (existing.isPresent()) {
            ProblemGuidance guidance = existing.get();
            guidance.setSource(source);
            guidance.setClarifyText(clarifyText);
            guidance.setObservationText(observationText);
            guidance.setApproachText(approachText);
            guidance.setExplanationText(explanationText);
            guidance.setPrerequisitesEncoded(prerequisitesEncoded);
            guidance.setReferenceLinksEncoded(referenceLinksEncoded);
            guidanceRepository.update(guidance);
            return guidanceRepository.findByProblemId(problemId).orElseThrow();
        }
        ProblemGuidance guidance = new ProblemGuidance(0, problemId, source, clarifyText, observationText,
                approachText, explanationText, prerequisitesEncoded, referenceLinksEncoded, null, null);
        return guidanceRepository.save(guidance);
    }

    private String encodeOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String encoded = AcceptedAnswerCodec.encode(values);
        return encoded.isBlank() ? null : encoded;
    }

    /** The highest hint level opened so far in the current attempt, or empty if none has been opened
     *  (or there's no active/resumable session at all yet). */
    public Optional<HintLevel> getOpenedLevel(long problemId) {
        return sessionRepository.findByProblemId(problemId).map(ProblemSolvingSession::getHighestHintLevelOpened);
    }

    /**
     * Opens exactly the next hint level — {@link HintLevel#CLARIFY} if none is open yet, otherwise
     * one step past whatever is currently the highest opened level. Never skips ahead: a caller can't
     * jump straight to {@link HintLevel#EXPLANATION} by calling this once. Calling this again once
     * already at {@link HintLevel#EXPLANATION} is a harmless no-op that just re-reveals it.
     */
    public HintReveal openNextHintLevel(long problemId) {
        ProblemSolvingSession session = sessionRepository.findByProblemId(problemId)
                .orElseGet(() -> sessionRepository.save(ProblemSolvingSession.start(problemId)));
        HintLevel current = session.getHighestHintLevelOpened();
        HintLevel toReveal = current == null ? HintLevel.CLARIFY : current.next() != null ? current.next() : current;
        if (toReveal != current) {
            session.setHighestHintLevelOpened(toReveal);
            sessionRepository.update(session);
        }
        return revealLevel(problemId, toReveal);
    }

    /** Reveals a specific level's content directly (e.g. re-displaying an already-opened level)
     *  without changing what's recorded as opened. */
    public HintReveal revealLevel(long problemId, HintLevel level) {
        String text = getGuidance(problemId).map(guidance -> guidance.textForLevel(level)).orElse(null);
        boolean hasContent = text != null && !text.isBlank();
        return new HintReveal(level, hasContent ? text : null, hasContent);
    }

    /**
     * The assistance level implied by how far up the hint ladder a learner went this attempt (#162):
     * no hint opened is {@link SolvedWith#SELF}; any of the first three levels (still teaching
     * reasoning, not handing over the full answer) is {@link SolvedWith#HINT}; opening the full
     * {@link HintLevel#EXPLANATION} is treated as {@link SolvedWith#EDITORIAL}, since that level's
     * content already <em>is</em> CodeFit's own editorial-equivalent explanation.
     * {@link SolvedWith#SOLUTION} is deliberately never returned here — none of the four hint levels
     * are "here is a ready-made solution to copy", so that distinction stays a manual, learner-chosen
     * reflection value rather than something this ladder can infer.
     */
    public SolvedWith computeAssistanceLevel(HintLevel maxOpened) {
        if (maxOpened == null) {
            return SolvedWith.SELF;
        }
        return maxOpened == HintLevel.EXPLANATION ? SolvedWith.EDITORIAL : SolvedWith.HINT;
    }

    /** What a hint-ladder request revealed: {@code text} is {@code null} exactly when
     *  {@code hasContent} is {@code false} — the caller must show "no guidance authored yet" rather
     *  than treating a missing level as if it had nothing to say. */
    public record HintReveal(HintLevel level, String text, boolean hasContent) {
    }
}
