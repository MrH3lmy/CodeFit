package com.codefit.service;

import com.codefit.model.AssessmentAttempt;
import com.codefit.model.AssessmentItem;
import com.codefit.model.AssessmentVariant;
import com.codefit.repository.AssessmentAttemptRepository;

import java.time.LocalDateTime;

/**
 * Records a graded transfer-assessment attempt in the assessment bank's own table only. This is the
 * single write path for assessment results, and it deliberately never touches
 * {@code FlashcardRepository} or {@code ReviewHistoryRepository} — assessment results must not
 * silently alter a normal card's schedule or history (#104). If a future feature wants assessment
 * evidence to influence a specific card's interval, that must be an explicit, separately reviewed,
 * opt-in mapping, not a side effect of this method.
 */
public class AssessmentAttemptService {
    private final AssessmentAttemptRepository assessmentAttemptRepository = new AssessmentAttemptRepository();

    public AssessmentAttempt recordAttempt(AssessmentItem item, AssessmentVariant variant, boolean correct,
                                           String submittedAnswer, Integer responseTimeMs, String runId) {
        AssessmentAttempt attempt = new AssessmentAttempt(0, item.getId(), variant.variantIndex(),
                item.getSkillCategory(), item.getModuleName(), correct, submittedAnswer, responseTimeMs,
                LocalDateTime.now(), runId);
        return assessmentAttemptRepository.save(attempt);
    }
}
