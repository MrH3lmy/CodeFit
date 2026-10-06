package com.codefit.ui;

import com.codefit.peer.protocol.MetricUnit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure, stateless formatting for the Peers screen's comparison UI: turns a wire metric id and its
 * {@link MetricUnit}/value into the same human-readable product copy a learner would expect
 * anywhere else in CodeFit - never a raw internal id, never a raw basis-points/seconds integer.
 * Deliberately has no JavaFX dependency so it can be unit-tested directly, independent of whether a
 * display is available to run the JavaFX toolkit in a given environment.
 *
 * <p>This is presentation only: it makes no comparability, authorization, or staleness decision -
 * those remain exactly where they already are, in {@code SnapshotComparisonEngine}/{@code
 * PeerComparisonService}/{@code PeerMatchService}.
 */
public final class PeerMetricPresentation {

    private static final DateTimeFormatter CUTOFF_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private PeerMetricPresentation() {
    }

    /**
     * The product-facing label for a metric id, for every metric {@code MetricRegistry} currently
     * defines. An id this build does not yet recognize still gets a readable label (never a raw dotted
     * id) via {@link #humanizeUnknownId}, so a newer peer's unfamiliar metric degrades gracefully.
     */
    public static String label(String metricId) {
        return switch (metricId) {
            case "review.attempts" -> "Reviews";
            case "review.verified_correct_rate" -> "Review accuracy";
            case "review.self_rated_success_rate" -> "Self-rated success";
            case "review.hint_free_rate" -> "Hint-free reviews";
            case "review.legacy_rating_fallback_success_rate" -> "Legacy review success";
            case "problem.attempts" -> "Problems attempted";
            case "problem.accepted" -> "Problems accepted";
            case "problem.unique_completed" -> "Problems solved";
            case "problem.solving_seconds" -> "Problem-solving time";
            case "mock.overall_score" -> "Mock interview score";
            default -> humanizeUnknownId(metricId);
        };
    }

    /**
     * Formats one side's already-{@code MEASURED} integer value for its unit: {@code COUNT} as a plain
     * integer, {@code SECONDS} as human-readable duration ({@link #formatSeconds}), {@code
     * BASIS_POINTS}/{@code PERCENT} as a whole-number percentage. Callers must never pass a value for a
     * metric whose availability is not {@code MEASURED} - that case has no meaningful number to show at
     * all (see {@code MetricValue}'s own javadoc: "value must be 0 and is not displayed").
     */
    public static String formatValue(MetricUnit unit, long value) {
        return switch (unit) {
            case COUNT -> Long.toString(value);
            case SECONDS -> formatSeconds(value);
            // 1 basis point = 0.01%, so a whole-number percentage is value / 100.
            case BASIS_POINTS -> Math.round(value / 100.0) + "%";
            case PERCENT -> value + "%";
        };
    }

    /**
     * Human-readable duration: under a minute as seconds, under an hour as whole minutes (rounded to
     * the nearest minute), otherwise hours and minutes (minutes omitted entirely when exactly zero).
     * Examples: 45 sec, 8 min, 1h 12m, 2h.
     */
    public static String formatSeconds(long totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + " sec";
        }
        long totalMinutes = Math.round(totalSeconds / 60.0);
        if (totalMinutes < 60) {
            return totalMinutes + " min";
        }
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
    }

    /** "Compared through 5:37 PM" - always derived from the real comparison cutoff, never {@code Instant.now()}. */
    public static String formatCutoff(Instant cutoff, ZoneId zone) {
        return CUTOFF_FORMAT.format(cutoff.atZone(zone));
    }

    private static String humanizeUnknownId(String metricId) {
        String[] words = metricId.replace('.', ' ').replace('_', ' ').trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? metricId : result.toString();
    }
}
