package com.codefit.peer.protocol;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;

/**
 * One calendar DAY or WEEK as a half-open UTC interval {@code [start, end)}, labelled with the local
 * start date, IANA zone, and (for weeks) week-start day that produced it.
 *
 * <p>The UTC instants are authoritative. A receiver never recomputes them from the label with its own
 * tzdata, because two JDKs with different tz rules could disagree; the label exists for display and so
 * a receiver can tell whether two windows describe "the same" local period. Durations are bounded
 * (DAY 22-26h, WEEK 166-170h) to admit DST and half-hour shifts while rejecting nonsense.
 *
 * @param weekStart required for WEEK (and must equal {@code localStartDate}'s day of week), absent for DAY
 */
public record ComparisonWindow(WindowKind kind, LocalDate localStartDate, String zoneId, DayOfWeek weekStart,
                               Instant start, Instant end) {
    private static final Duration MIN_DAY = Duration.ofHours(22);
    private static final Duration MAX_DAY = Duration.ofHours(26);
    private static final Duration MIN_WEEK = Duration.ofHours(7 * 24 - 2);
    private static final Duration MAX_WEEK = Duration.ofHours(7 * 24 + 2);
    /** Bounds exactly the predefined Study Match duration set (15/30/60 minutes) - see {@link MatchDuration}. */
    private static final Duration MIN_MATCH = Duration.ofMinutes(15);
    private static final Duration MAX_MATCH = Duration.ofMinutes(60);

    public ComparisonWindow {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(localStartDate, "localStartDate");
        zoneId = SocialProfileCard.zoneId(zoneId);
        ProtocolTime.toWireMillis(Objects.requireNonNull(start, "start"), "window start");
        ProtocolTime.toWireMillis(Objects.requireNonNull(end, "end"), "window end");
        Duration length = Duration.between(start, end);
        switch (kind) {
            case DAY -> {
                if (weekStart != null) {
                    throw new IllegalArgumentException("A DAY window carries no week start.");
                }
                if (length.compareTo(MIN_DAY) < 0 || length.compareTo(MAX_DAY) > 0) {
                    throw new IllegalArgumentException("DAY window length out of bounds: " + length);
                }
            }
            case WEEK -> {
                if (weekStart == null || localStartDate.getDayOfWeek() != weekStart) {
                    throw new IllegalArgumentException("A WEEK window must start on its declared week-start day.");
                }
                if (length.compareTo(MIN_WEEK) < 0 || length.compareTo(MAX_WEEK) > 0) {
                    throw new IllegalArgumentException("WEEK window length out of bounds: " + length);
                }
            }
            case MATCH -> {
                if (weekStart != null) {
                    throw new IllegalArgumentException("A MATCH window carries no week start.");
                }
                if (length.compareTo(MIN_MATCH) < 0 || length.compareTo(MAX_MATCH) > 0) {
                    throw new IllegalArgumentException("MATCH window length out of bounds: " + length);
                }
            }
        }
    }

    /** The local calendar day {@code date} in {@code zone}: local midnight to next local midnight. */
    public static ComparisonWindow day(LocalDate date, ZoneId zone) {
        return new ComparisonWindow(WindowKind.DAY, date, zone.getId(), null,
                date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * The local week containing {@code anyDayInWeek}, beginning on {@code weekStart} in {@code
     * zone}.
     */
    public static ComparisonWindow week(LocalDate anyDayInWeek, ZoneId zone, DayOfWeek weekStart) {
        LocalDate first = anyDayInWeek.with(TemporalAdjusters.previousOrSame(weekStart));
        return new ComparisonWindow(WindowKind.WEEK, first, zone.getId(), weekStart,
                first.atStartOfDay(zone).toInstant(), first.plusWeeks(1).atStartOfDay(zone).toInstant());
    }

    /**
     * The explicit interval {@code [startedAt, endsAt)} a Study Match's two participants already
     * agreed on (the challenger's chosen duration, fixed to an authoritative instant by the
     * opponent's acceptance - see {@code MatchResponse}). Unlike {@link #day}/{@link #week}, this is
     * never independently recomputed from a local date and zone: both participants construct this
     * same window from the identical shared instants, so {@code localStartDate}/{@code zoneId} here
     * are canonical display metadata only (this device's own UTC date, fixed regardless of either
     * participant's real timezone) - never authoritative, exactly like every other window kind's own
     * UTC start/end.
     */
    public static ComparisonWindow match(Instant startedAt, Instant endsAt) {
        LocalDate canonicalDate = startedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return new ComparisonWindow(WindowKind.MATCH, canonicalDate, "UTC", null, startedAt, endsAt);
    }

    public Duration length() {
        return Duration.between(start, end);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /** The data cutoff for a summary produced at {@code now}: clamped into {@code [start, end]}. */
    public Instant cutoffAt(Instant now) {
        if (now.isBefore(start)) {
            return start;
        }
        return now.isAfter(end) ? end : now;
    }

    /**
     * Cutoff that makes an in-progress period comparable with another period by elapsed time: "my week
     * so far" versus "their week up to the same elapsed point". Clamped to this window's length.
     */
    public Instant equalElapsedCutoff(Duration elapsed) {
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("Elapsed time must not be negative.");
        }
        return elapsed.compareTo(length()) >= 0 ? end : start.plus(elapsed);
    }

    /** Same kind and same local label (date, zone, week start) - "the same local period". */
    public boolean sameLocalPeriodAs(ComparisonWindow other) {
        return kind == other.kind && localStartDate.equals(other.localStartDate)
                && zoneId.equals(other.zoneId) && Objects.equals(weekStart, other.weekStart);
    }

    void writeTo(CanonicalWriter writer) {
        writer.u8(kind.code()).i64(localStartDate.toEpochDay()).string(zoneId, SocialProfileCard.MAX_ZONE_ID_BYTES);
        if (kind == WindowKind.WEEK) {
            writer.u8(weekStart.getValue());
        }
        writer.i64(start.toEpochMilli()).i64(end.toEpochMilli());
    }

    static ComparisonWindow readFrom(CanonicalReader reader) {
        WindowKind kind = WireCode.fromCode(WindowKind.class, reader.u8());
        long epochDay = reader.i64();
        LocalDate date;
        try {
            date = LocalDate.ofEpochDay(epochDay);
        } catch (DateTimeException e) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Local start date out of range.");
        }
        String zone = reader.string(SocialProfileCard.MAX_ZONE_ID_BYTES);
        DayOfWeek weekStart = kind == WindowKind.WEEK ? dayOfWeek(reader.u8()) : null;
        Instant start = ProtocolTime.fromWireMillis(reader.i64(), "window start");
        Instant end = ProtocolTime.fromWireMillis(reader.i64(), "window end");
        return new ComparisonWindow(kind, date, zone, weekStart, start, end);
    }

    static DayOfWeek dayOfWeek(int isoValue) {
        if (isoValue < 1 || isoValue > 7) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Day of week must be ISO 1..7, got " + isoValue);
        }
        return DayOfWeek.of(isoValue);
    }
}
