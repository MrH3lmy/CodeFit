package com.codefit.service;

import com.codefit.model.UserProgress;
import com.codefit.repository.UserProgressRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The solving workspace's coaching-checkpoint preference (#145): reminders only, shown at
 * configurable total-elapsed-minute thresholds (20/60/120 minutes by default), never enforced or
 * blocking. Stored as plain columns on {@code user_progress}, the same way every other CodeFit
 * preference (theme, daily new-card limit, guided session length, etc.) already is.
 */
public class SolvingCheckpointPreferenceService {

    private final UserProgressRepository userProgressRepository;

    public SolvingCheckpointPreferenceService() {
        this(new UserProgressRepository());
    }

    public SolvingCheckpointPreferenceService(UserProgressRepository userProgressRepository) {
        this.userProgressRepository = userProgressRepository;
    }

    public boolean isCheckpointsEnabled() {
        return userProgressRepository.getProgress().isSolvingCheckpointsEnabled();
    }

    public void setCheckpointsEnabled(boolean enabled) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setSolvingCheckpointsEnabled(enabled);
        userProgressRepository.save(progress);
    }

    public List<Integer> getCheckpointMinutes() {
        return userProgressRepository.getProgress().getSolvingCheckpointMinutes();
    }

    /** Stores the given minute thresholds, deduplicated and sorted ascending; blank/non-positive values are dropped. */
    public void setCheckpointMinutes(List<Integer> minutes) {
        List<Integer> cleaned = new ArrayList<>();
        for (Integer minute : minutes == null ? List.<Integer>of() : minutes) {
            if (minute != null && minute > 0 && !cleaned.contains(minute)) {
                cleaned.add(minute);
            }
        }
        cleaned.sort(Comparator.naturalOrder());
        String csv = cleaned.isEmpty() ? UserProgress.DEFAULT_SOLVING_CHECKPOINT_MINUTES
                : String.join(",", cleaned.stream().map(String::valueOf).toList());

        UserProgress progress = userProgressRepository.getProgress();
        progress.setSolvingCheckpointMinutesCsv(csv);
        userProgressRepository.save(progress);
    }

    /**
     * If checkpoints are enabled, returns the single checkpoint threshold (in minutes) that was
     * newly crossed going from {@code previousTotalSeconds} to {@code currentTotalSeconds} — i.e. a
     * threshold at or below the previous total was already reminded about (or never will be), and a
     * threshold above the current total hasn't been reached yet. Returns empty when checkpoints are
     * disabled, or when no threshold was crossed in this step.
     */
    public Optional<Integer> findNewlyCrossedCheckpoint(int previousTotalSeconds, int currentTotalSeconds) {
        if (!isCheckpointsEnabled()) {
            return Optional.empty();
        }
        return getCheckpointMinutes().stream()
                .filter(minutes -> previousTotalSeconds < minutes * 60L && currentTotalSeconds >= minutes * 60L)
                .min(Comparator.naturalOrder());
    }
}
