package com.codefit.service;

import com.codefit.model.InterviewPreparationProfile;

import java.util.List;
import java.util.Optional;

/**
 * Simple explicit registry of interview-preparation profiles - currently just Revolut Java Senior
 * Software Engineer. An interview profile is a cross-cutting composition of existing training-path
 * material (see {@link RevolutJavaInterviewProfile}), not another sequential {@link TrainingPathService}
 * path, so this deliberately does not read or modify any training-path state.
 *
 * <p>Adding a second company's profile means writing one more static-definition class and adding it
 * to {@link #PROFILES} - no reflection-based registry or plugin mechanism is needed for this.
 */
public class InterviewProfileService {
    private static final List<InterviewPreparationProfile> PROFILES = List.of(RevolutJavaInterviewProfile.build());

    public List<InterviewPreparationProfile> getProfiles() {
        return PROFILES;
    }

    public InterviewPreparationProfile getRevolutJavaProfile() {
        return findProfile(RevolutJavaInterviewProfile.ID)
                .orElseThrow(() -> new IllegalStateException("Revolut Java interview profile is not registered."));
    }

    public Optional<InterviewPreparationProfile> findProfile(String profileId) {
        return PROFILES.stream()
                .filter(profile -> profile.getId().equals(profileId))
                .findFirst();
    }

    /** Structural validation for a registered profile; see {@link InterviewPreparationProfile#validate()}. */
    public List<String> validateProfile(InterviewPreparationProfile profile) {
        return profile.validate();
    }
}
