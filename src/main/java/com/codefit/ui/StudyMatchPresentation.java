package com.codefit.ui;

import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.StudyMatch;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Pure view-model for a peer card's Study Match block. Maps the latest {@link StudyMatch} (or none)
 * plus whether the peer is connected onto exactly one of seven intentional states, each with its own
 * wording and the set of actions that make sense in it - the state-to-action mapping the Peers screen
 * used to scatter across four booleans in the controller. It decides nothing about the match itself:
 * lifecycle, ownership and the "who is ahead" verdict all stay in {@code PeerMatchService}. No JavaFX
 * dependency.
 */
public record StudyMatchPresentation(State state, String stateLabel, Tone tone, String detail, String offlineNote,
                                     boolean canStart, boolean canAccept, boolean canDecline, boolean canCancel,
                                     boolean canRefresh, String refreshLabel, String startLabel, Instant endsAt,
                                     int minutes) {

    public enum State {
        NONE, PENDING_OUTGOING, PENDING_INCOMING, ACTIVE, COMPLETED, DECLINED, CANCELLED
    }

    /** Semantic colour of the state pill: green active, amber waiting/invited, neutral otherwise. */
    public enum Tone {
        NEUTRAL, WAITING, SUCCESS
    }

    public static StudyMatchPresentation of(Optional<StudyMatch> latest, boolean connected, String peerName, Instant now) {
        if (latest.isEmpty()) {
            return new StudyMatchPresentation(State.NONE, null, Tone.NEUTRAL,
                    "Challenge " + peerName + " to a focused study session.", null,
                    true, false, false, false, false, null, "Start match", null, 0);
        }
        StudyMatch match = latest.get();
        int minutes = match.duration().minutes();
        boolean challenger = match.role() == MatchRole.CHALLENGER;
        return switch (match.status()) {
            case PENDING -> challenger
                    ? new StudyMatchPresentation(State.PENDING_OUTGOING, "Waiting", Tone.WAITING,
                            minutes + " min · Waiting for " + peerName + " to accept…", null,
                            false, false, false, true, false, null, null, null, minutes)
                    : new StudyMatchPresentation(State.PENDING_INCOMING, "Invitation", Tone.WAITING,
                            PeerNamePresentation.capitalize(peerName) + " invited you to a " + minutes + "-minute Study Match.", null,
                            false, true, true, false, false, null, null, null, minutes);
            case ACTIVE -> new StudyMatchPresentation(State.ACTIVE, "Active", Tone.SUCCESS,
                    activeDetail(match.endsAt(), now, minutes),
                    connected ? null : PeerNamePresentation.capitalize(peerName) + " is offline. Showing last synced progress.",
                    false, false, false, false, true, "Refresh progress", null, match.endsAt(), minutes);
            case COMPLETED -> new StudyMatchPresentation(State.COMPLETED, "Completed", Tone.NEUTRAL,
                    minutes + " min match finished.", null,
                    true, false, false, false, true, "View results", "Start a new match", null, minutes);
            case DECLINED -> new StudyMatchPresentation(State.DECLINED, "Declined", Tone.NEUTRAL,
                    (challenger ? PeerNamePresentation.capitalize(peerName) + " declined" : "You declined") + " the " + minutes + " min match.", null,
                    true, false, false, false, false, null, "Start a new match", null, minutes);
            case CANCELLED -> new StudyMatchPresentation(State.CANCELLED, "Cancelled", Tone.NEUTRAL,
                    (challenger ? "You cancelled" : PeerNamePresentation.capitalize(peerName) + " cancelled") + " the " + minutes + " min match.", null,
                    true, false, false, false, false, null, "Start a new match", null, minutes);
        };
    }

    private static String activeDetail(Instant endsAt, Instant now, int minutes) {
        return remainingText(endsAt, now) + " · " + minutes + " min match";
    }

    /** The detail line as it should read at {@code now} - only an {@link State#ACTIVE} match's changes over time. */
    public String detailAt(Instant now) {
        return state == State.ACTIVE && endsAt != null ? activeDetail(endsAt, now, minutes) : detail;
    }

    /** "08:42 remaining" (or "1:05:00 remaining" past an hour); never negative. */
    public static String remainingText(Instant endsAt, Instant now) {
        Duration remaining = Duration.between(now, endsAt);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        long total = remaining.toSeconds();
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        String clock = hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
        return clock + " remaining";
    }

    /** What the "who is ahead" verdict PeerMatchService already derived says, in one short line. */
    public static String verdictText(com.codefit.service.PeerMatchService.LeaderVerdict verdict, String peerName) {
        return switch (verdict) {
            case AHEAD -> "You're ahead.";
            case BEHIND -> "You're behind " + peerName + ".";
            case TIED -> "You're tied.";
            case MIXED -> "Mixed results. No consistent leader across comparable metrics.";
        };
    }
}
