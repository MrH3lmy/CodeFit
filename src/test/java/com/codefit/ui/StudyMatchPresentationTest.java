package com.codefit.ui;

import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.service.PeerMatchService;
import com.codefit.ui.StudyMatchPresentation.State;
import com.codefit.ui.StudyMatchPresentation.Tone;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudyMatchPresentationTest {
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private static StudyMatch match(MatchRole role, MatchStatus status, MatchDuration duration, Instant endsAt) {
        boolean timed = status == MatchStatus.ACTIVE || status == MatchStatus.COMPLETED;
        return new StudyMatch(new ObjectId(new byte[ObjectId.LENGTH]), 1L, role, duration, status, NOW, timed ? NOW : null,
                timed ? endsAt.minus(java.time.Duration.ofMinutes(duration.minutes())) : null, timed ? endsAt : null, NOW);
    }

    private static StudyMatchPresentation of(Optional<StudyMatch> match, boolean connected) {
        return StudyMatchPresentation.of(match, connected, "Ahmed", NOW);
    }

    @Test
    void noMatchOffersOnlyStarting() {
        StudyMatchPresentation view = of(Optional.empty(), true);
        assertEquals(State.NONE, view.state());
        assertEquals("Challenge Ahmed to a focused study session.", view.detail());
        assertNull(view.stateLabel(), "nothing to badge yet");
        assertTrue(view.canStart());
        assertFalse(view.canAccept() || view.canDecline() || view.canCancel() || view.canRefresh());
        assertEquals("Start match", view.startLabel());
    }

    @Test
    void aPendingInvitationIAmWaitingOnOffersOnlyCancel() {
        StudyMatchPresentation view = of(Optional.of(match(MatchRole.CHALLENGER, MatchStatus.PENDING, MatchDuration.FIFTEEN_MINUTES, null)), true);
        assertEquals(State.PENDING_OUTGOING, view.state());
        assertEquals("15 min · Waiting for Ahmed to accept…", view.detail());
        assertEquals(Tone.WAITING, view.tone());
        assertTrue(view.canCancel());
        assertFalse(view.canStart() || view.canAccept() || view.canDecline() || view.canRefresh());
    }

    @Test
    void anInvitationFromThePeerOffersAcceptAndDeclineOnly() {
        StudyMatchPresentation view = of(Optional.of(match(MatchRole.OPPONENT, MatchStatus.PENDING, MatchDuration.THIRTY_MINUTES, null)), true);
        assertEquals(State.PENDING_INCOMING, view.state());
        assertEquals("Ahmed invited you to a 30-minute Study Match.", view.detail());
        assertTrue(view.canAccept() && view.canDecline());
        assertFalse(view.canStart() || view.canCancel() || view.canRefresh());
    }

    @Test
    void anActiveMatchShowsATimeRemainingAndOnlyRefresh() {
        Instant ends = NOW.plusSeconds(8 * 60 + 42);
        StudyMatchPresentation view = of(Optional.of(match(MatchRole.CHALLENGER, MatchStatus.ACTIVE, MatchDuration.FIFTEEN_MINUTES, ends)), true);
        assertEquals(State.ACTIVE, view.state());
        assertEquals("Active", view.stateLabel());
        assertEquals(Tone.SUCCESS, view.tone());
        assertEquals("08:42 remaining · 15 min match", view.detail());
        assertEquals("Refresh progress", view.refreshLabel());
        assertTrue(view.canRefresh());
        assertFalse(view.canStart() || view.canCancel() || view.canAccept() || view.canDecline());
        assertNull(view.offlineNote());
        assertEquals("04:42 remaining · 15 min match", view.detailAt(NOW.plusSeconds(4 * 60)), "the countdown re-derives from the real end time");
    }

    @Test
    void anActiveMatchWithAnOfflinePeerSaysTheNumbersAreLastSynced() {
        StudyMatchPresentation view = of(Optional.of(match(MatchRole.CHALLENGER, MatchStatus.ACTIVE, MatchDuration.FIFTEEN_MINUTES, NOW.plusSeconds(600))), false);
        assertEquals("Ahmed is offline. Showing last synced progress.", view.offlineNote());
        assertTrue(view.canRefresh(), "refresh still works offline - it shows what was last synced");
    }

    @Test
    void aCompletedMatchOffersResultsAndANewMatch() {
        StudyMatchPresentation view = of(Optional.of(match(MatchRole.CHALLENGER, MatchStatus.COMPLETED, MatchDuration.SIXTY_MINUTES, NOW.minusSeconds(5))), true);
        assertEquals(State.COMPLETED, view.state());
        assertEquals("Completed", view.stateLabel());
        assertEquals("View results", view.refreshLabel());
        assertTrue(view.canRefresh() && view.canStart());
        assertEquals("Start a new match", view.startLabel());
    }

    @Test
    void declinedAndCancelledMatchesSayWhoDidItAndOfferANewMatch() {
        StudyMatchPresentation declinedByThem = of(Optional.of(match(MatchRole.CHALLENGER, MatchStatus.DECLINED, MatchDuration.FIFTEEN_MINUTES, null)), true);
        assertEquals("Ahmed declined the 15 min match.", declinedByThem.detail());
        StudyMatchPresentation declinedByMe = of(Optional.of(match(MatchRole.OPPONENT, MatchStatus.DECLINED, MatchDuration.FIFTEEN_MINUTES, null)), true);
        assertEquals("You declined the 15 min match.", declinedByMe.detail());
        StudyMatchPresentation cancelledByMe = of(Optional.of(match(MatchRole.CHALLENGER, MatchStatus.CANCELLED, MatchDuration.FIFTEEN_MINUTES, null)), true);
        assertEquals("You cancelled the 15 min match.", cancelledByMe.detail());
        StudyMatchPresentation cancelledByThem = of(Optional.of(match(MatchRole.OPPONENT, MatchStatus.CANCELLED, MatchDuration.THIRTY_MINUTES, null)), true);
        assertEquals("Ahmed cancelled the 30 min match.", cancelledByThem.detail());
        for (StudyMatchPresentation view : new StudyMatchPresentation[]{declinedByThem, declinedByMe, cancelledByMe, cancelledByThem}) {
            assertTrue(view.canStart());
            assertFalse(view.canRefresh() || view.canAccept() || view.canDecline() || view.canCancel());
        }
    }

    @Test
    void exactlyTheDocumentedActionsExistInEveryState() {
        // Guards the state -> action mapping the old controller kept in four booleans.
        for (MatchRole role : MatchRole.values()) {
            for (MatchStatus status : MatchStatus.values()) {
                boolean timed = status == MatchStatus.ACTIVE || status == MatchStatus.COMPLETED;
                StudyMatchPresentation view = of(Optional.of(match(role, status, MatchDuration.FIFTEEN_MINUTES, timed ? NOW.plusSeconds(60) : null)), true);
                boolean terminal = status == MatchStatus.COMPLETED || status == MatchStatus.DECLINED || status == MatchStatus.CANCELLED;
                assertEquals(terminal, view.canStart(), role + "/" + status);
                assertEquals(role == MatchRole.OPPONENT && status == MatchStatus.PENDING, view.canAccept(), role + "/" + status);
                assertEquals(role == MatchRole.OPPONENT && status == MatchStatus.PENDING, view.canDecline(), role + "/" + status);
                assertEquals(role == MatchRole.CHALLENGER && status == MatchStatus.PENDING, view.canCancel(), role + "/" + status);
                assertEquals(status == MatchStatus.ACTIVE || status == MatchStatus.COMPLETED, view.canRefresh(), role + "/" + status);
            }
        }
    }

    @Test
    void remainingTimeFormatsAsClockAndNeverGoesNegative() {
        assertEquals("08:42 remaining", StudyMatchPresentation.remainingText(NOW.plusSeconds(522), NOW));
        assertEquals("1:05:00 remaining", StudyMatchPresentation.remainingText(NOW.plusSeconds(3900), NOW));
        assertEquals("00:00 remaining", StudyMatchPresentation.remainingText(NOW.minusSeconds(30), NOW));
    }

    @Test
    void verdictsStayExactlyAsDerivedByTheMatchService() {
        assertEquals("You're ahead.", StudyMatchPresentation.verdictText(PeerMatchService.LeaderVerdict.AHEAD, "Ahmed"));
        assertEquals("You're behind Ahmed.", StudyMatchPresentation.verdictText(PeerMatchService.LeaderVerdict.BEHIND, "Ahmed"));
        assertEquals("You're tied.", StudyMatchPresentation.verdictText(PeerMatchService.LeaderVerdict.TIED, "Ahmed"));
        assertTrue(StudyMatchPresentation.verdictText(PeerMatchService.LeaderVerdict.MIXED, "Ahmed").startsWith("Mixed"));
    }
}
