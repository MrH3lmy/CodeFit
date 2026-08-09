package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewRequirement;
import com.codefit.repository.InterviewMockRepository;

import java.util.List;

/**
 * Resolves durable mock-interview performance into one readiness signal for an interview domain.
 * Two samples are required before the signal becomes measurable; once measurable, the latest three
 * samples are averaged so one unusually strong or weak run cannot dominate indefinitely.
 */
class MockInterviewReadinessResolver implements InterviewRequirementReadinessResolver {
    static final int MIN_MEASURABLE_SAMPLES = 2;
    static final int RECENT_SAMPLE_LIMIT = 3;
    static final String KEY_SEPARATOR = "::";

    private final InterviewMockRepository repository;

    MockInterviewReadinessResolver() {
        this(new InterviewMockRepository());
    }

    MockInterviewReadinessResolver(InterviewMockRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean supports(InterviewMaterialType type) {
        return type == InterviewMaterialType.MOCK_INTERVIEW;
    }

    @Override
    public InterviewRequirementReadiness resolve(InterviewRequirement requirement) {
        ReferenceParts parts = parseReference(requirement.getReference().key());
        List<InterviewMockRepository.StoredDomainScore> scores = repository.findRecentDomainScores(
                parts.profileId(), parts.domainId(), RECENT_SAMPLE_LIMIT);
        if (scores.size() < MIN_MEASURABLE_SAMPLES) {
            return InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.MOCK_INTERVIEW,
                    "Mock interview evidence needs at least " + MIN_MEASURABLE_SAMPLES + " scored samples; currently "
                            + scores.size() + ".");
        }
        double average = scores.stream().mapToInt(InterviewMockRepository.StoredDomainScore::scorePercent)
                .average().orElseThrow();
        return InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.MOCK_INTERVIEW, average,
                "Average of the latest " + scores.size() + " scored mock-interview samples for this domain.");
    }

    static InterviewRequirement requirementFor(String profileId, InterviewDomain domain) {
        return InterviewRequirement.available(
                "mock-evidence-" + domain.getId(),
                "Mock interview evidence - " + domain.getTitle(),
                "Direct scored performance from mock interview rubrics for this domain.",
                InterviewMaterialType.MOCK_INTERVIEW,
                referenceKey(profileId, domain.getId()));
    }

    static String referenceKey(String profileId, String domainId) {
        if (profileId == null || profileId.isBlank() || domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("Mock interview readiness reference requires profile and domain ids.");
        }
        return profileId + KEY_SEPARATOR + domainId;
    }

    private static ReferenceParts parseReference(String key) {
        int separatorIndex = key.indexOf(KEY_SEPARATOR);
        if (separatorIndex <= 0 || separatorIndex == key.length() - KEY_SEPARATOR.length()) {
            throw new IllegalArgumentException("Invalid mock interview readiness reference key: " + key);
        }
        String profileId = key.substring(0, separatorIndex);
        String domainId = key.substring(separatorIndex + KEY_SEPARATOR.length());
        return new ReferenceParts(profileId, domainId);
    }

    private record ReferenceParts(String profileId, String domainId) {
    }
}
