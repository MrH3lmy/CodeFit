package com.codefit.service;

import com.codefit.peer.identity.SocialProfile;
import com.codefit.repository.SocialProfileRepository;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Optional;

/**
 * Owns the learner's own editable social profile (#181), entirely separate from
 * {@code InterviewPreparationProfile} and from identity: nothing here ever touches
 * {@code peer_identity}, so renaming or re-describing the profile can never change or lose identity.
 * Duplicate display names across contacts, and against the learner's own past names, are valid by
 * construction — there is no uniqueness constraint anywhere in this class or its table.
 */
public class SocialProfileService {
    private final SocialProfileRepository profileRepository;

    public SocialProfileService() {
        this(new SocialProfileRepository());
    }

    SocialProfileService(SocialProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public Optional<SocialProfile> currentProfile() {
        return profileRepository.find();
    }

    /** Creates the profile on first call, or edits every text field on later calls; the avatar is untouched. */
    public SocialProfile editProfile(String displayName, String bio, String comparisonZoneId, DayOfWeek weekStart, Instant now) {
        Optional<SocialProfile> existing = profileRepository.find();
        byte[] avatarBytes = existing.map(SocialProfile::avatarBytes).orElse(null);
        String avatarMimeType = existing.map(SocialProfile::avatarMimeType).orElse(null);
        long nextRevision = existing.map(profile -> profile.revision() + 1).orElse(1L);
        SocialProfile updated = new SocialProfile(displayName, bio, comparisonZoneId, weekStart, avatarBytes,
                avatarMimeType, nextRevision, now);
        profileRepository.save(updated, now);
        return updated;
    }

    /** @throws IllegalStateException no profile exists yet; call {@link #editProfile} first */
    public SocialProfile setAvatar(byte[] avatarBytes, String mimeType, Instant now) {
        SocialProfile existing = requireProfile();
        SocialProfile updated = new SocialProfile(existing.displayName(), existing.bio(), existing.comparisonZoneId(),
                existing.weekStart(), avatarBytes, mimeType, existing.revision() + 1, now);
        profileRepository.save(updated, now);
        return updated;
    }

    public SocialProfile clearAvatar(Instant now) {
        SocialProfile existing = requireProfile();
        SocialProfile updated = new SocialProfile(existing.displayName(), existing.bio(), existing.comparisonZoneId(),
                existing.weekStart(), null, null, existing.revision() + 1, now);
        profileRepository.save(updated, now);
        return updated;
    }

    private SocialProfile requireProfile() {
        return profileRepository.find().orElseThrow(() -> new IllegalStateException("No social profile exists yet."));
    }
}
