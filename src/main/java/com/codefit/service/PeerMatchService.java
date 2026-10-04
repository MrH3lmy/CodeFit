package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.comparison.SnapshotComparisonEngine;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState;
import com.codefit.peer.comparison.SnapshotComparisonEngine.MetricComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ProgressComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.SnapshotObservation;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.snapshot.LocalProgressSnapshot;
import com.codefit.peer.sync.PeerProgressSummary;
import com.codefit.repository.MatchRepository;
import com.codefit.repository.PeerProgressSummaryRepository;
import com.codefit.repository.PeerSyncConsentRepository;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestration only, for exactly one product slice: "1-v-1 Study Match". Every actual comparison
 * decision still comes from {@link SnapshotComparisonEngine}, never duplicated here - this class's
 * own job is: own the match lifecycle ({@link MatchRepository}), reuse the existing evidence/
 * snapshot/outbox machinery for match-window progress, and derive the one small, explicitly
 * documented "who is ahead" verdict this feature's own brief asks for (never a new scoring system).
 *
 * <h2>Scope</h2>
 * {@link SharingScope#MATCH_PARTICIPATION} is granted automatically, mutually, by both {@link
 * #startMatch} (challenger, to the invited opponent) and {@link #accept} (opponent, to the
 * challenger) - never a separate manual consent toggle, since accepting an invitation already is
 * the consent.
 *
 * <h2>Transport</h2>
 * This class never touches {@code NetworkingService}, {@code PeerSyncSessionService}, or {@code
 * PeerConnection}: exactly like PR B's "Share Today's Progress", the caller (the controller)
 * decides whether to also call {@code NetworkingService#syncOutboxNow} right after a local action
 * that approved new data to send, if a connection to the contact happens to be live already.
 */
public class PeerMatchService {

    private final ContactService contactService;
    private final IdentityService identityService;
    private final ProgressSnapshotService progressSnapshotService;
    private final PeerProgressSummaryRepository peerProgressSummaryRepository;
    private final PeerSyncConsentRepository peerSyncConsentRepository;
    private final MatchRepository matchRepository;
    private final PeerSyncOutboxService outboxService;

    public PeerMatchService() {
        this(new ContactService(), new IdentityService(), new ProgressSnapshotService(), new PeerProgressSummaryRepository(),
                new PeerSyncConsentRepository(), new MatchRepository(), new PeerSyncOutboxService());
    }

    PeerMatchService(ContactService contactService, IdentityService identityService, ProgressSnapshotService progressSnapshotService,
                      PeerProgressSummaryRepository peerProgressSummaryRepository, PeerSyncConsentRepository peerSyncConsentRepository,
                      MatchRepository matchRepository, PeerSyncOutboxService outboxService) {
        this.contactService = contactService;
        this.identityService = identityService;
        this.progressSnapshotService = progressSnapshotService;
        this.peerProgressSummaryRepository = peerProgressSummaryRepository;
        this.peerSyncConsentRepository = peerSyncConsentRepository;
        this.matchRepository = matchRepository;
        this.outboxService = outboxService;
    }

    /** Who the comparable metrics favor - see {@link #deriveLeaderVerdict} for the exact, documented rule. */
    public enum LeaderVerdict {
        AHEAD, BEHIND, TIED, MIXED
    }

    /** {@code verdict} is empty exactly when {@code comparison.state()} is neither COMPARABLE nor DESCRIPTIVE_ONLY. */
    public record MatchProgressView(ProgressComparison comparison, Optional<LeaderVerdict> verdict) {
    }

    /**
     * The challenger's own action: proposes {@code duration} to {@code contactId}, granting them
     * {@link SharingScope#MATCH_PARTICIPATION} up front (harmless pre-commitment - nothing is
     * actually published under it until the match is accepted and a progress refresh is explicitly
     * requested) so no later, separate consent step is ever needed.
     */
    public StudyMatch startMatch(long contactId, MatchDuration duration, Instant now) {
        contactService.requireContact(contactId);
        contactService.grantAdditionalScope(contactId, SharingScope.MATCH_PARTICIPATION, 1, now);
        ObjectId matchId = randomMatchId();
        synchronized (PeerLocalWriteLock.MONITOR) {
            return matchRepository.createChallengerInvitation(contactId, matchId, duration, now);
        }
    }

    /**
     * The opponent's own acceptance: fixes the authoritative {@code startedAt} to {@code now} (see
     * {@code MatchResponse}'s own javadoc for why the challenger simply adopts this value verbatim
     * rather than computing its own), and grants the challenger {@code MATCH_PARTICIPATION} back.
     *
     * @throws IllegalStateException the match is not (still) {@code PENDING}
     */
    public StudyMatch accept(ObjectId matchId, Instant now) {
        StudyMatch pending = requireMatch(matchId);
        contactService.grantAdditionalScope(pending.contactId(), SharingScope.MATCH_PARTICIPATION, 1, now);
        synchronized (PeerLocalWriteLock.MONITOR) {
            return matchRepository.recordLocalResponse(matchId, true, now, now);
        }
    }

    /** @throws IllegalStateException the match is not (still) {@code PENDING} */
    public StudyMatch decline(ObjectId matchId, Instant now) {
        synchronized (PeerLocalWriteLock.MONITOR) {
            return matchRepository.recordLocalResponse(matchId, false, null, now);
        }
    }

    /** The challenger's own withdrawal. @throws IllegalStateException the match is not (still) {@code PENDING} */
    public StudyMatch cancel(ObjectId matchId, Instant now) {
        synchronized (PeerLocalWriteLock.MONITOR) {
            return matchRepository.recordLocalCancellation(matchId, now);
        }
    }

    /**
     * "Refresh Match Progress": captures this device's own real evidence up to the match's own
     * shared window (never a separately-invented cutoff) and approves it into the existing #184
     * outbox model, exactly like PR B's "Share Today's Progress" - the caller separately triggers
     * {@code NetworkingService#syncOutboxNow} if already connected.
     *
     * @throws IllegalStateException the match is not {@code ACTIVE}
     */
    public void refreshProgress(ObjectId matchId, Instant now) {
        StudyMatch match = requireActive(matchId, now);
        outboxService.approveProgressSummary(match.contactId(), match.window(), now);
    }

    public List<StudyMatch> matchesFor(long contactId) {
        return matchRepository.findByContact(contactId).stream()
                .map(match -> match.status() == MatchStatus.ACTIVE ? completeIfPastEndsAt(match.matchId(), Instant.now()) : match)
                .toList();
    }

    public Optional<StudyMatch> find(ObjectId matchId) {
        return matchRepository.find(matchId);
    }

    /**
     * Compares this device's own real match-window progress with the peer's - reusing the exact,
     * unmodified {@link SnapshotComparisonEngine}. Captures my own side fresh every call (cheap,
     * local, always safe - the same choice {@code PeerComparisonService} already makes); reading
     * the peer's side is a pure repository read (no network I/O - receiving already happens on the
     * connection's own automatic receive loop, independent of this call).
     *
     * <p>{@code evaluationInstant = min(now, match.endsAt())}, per this feature's own brief: both
     * participants' snapshots are declared "as of" their own real cutoff (not a separately-recorded
     * real-wall-clock capture/receive time), which is always {@code <= evaluationInstant} by
     * construction - see this method's own body for exactly why that avoids a false {@code
     * LEFT_CAPTURED_IN_FUTURE}/{@code RIGHT_CAPTURED_IN_FUTURE} once a match has completed and is
     * being viewed well after {@code endsAt}.
     *
     * @throws IllegalStateException the match is neither {@code ACTIVE} nor {@code COMPLETED}
     */
    public MatchProgressView compareProgress(ObjectId matchId, Instant now) {
        StudyMatch match = requireMatch(matchId);
        if (match.status() == MatchStatus.ACTIVE) {
            match = completeIfPastEndsAt(matchId, now);
        }
        if (match.status() != MatchStatus.ACTIVE && match.status() != MatchStatus.COMPLETED) {
            throw new IllegalStateException("Match " + matchId + " is " + match.status() + ", not ACTIVE/COMPLETED.");
        }
        Contact contact = contactService.requireContact(match.contactId());
        IdentityId peerId = contact.identityId();
        IdentityId myId = identityService.currentIdentity().map(LocalIdentitySummary::id)
                .orElseThrow(() -> new IllegalStateException("No local identity exists yet."));
        ComparisonWindow window = match.window();

        LocalProgressSnapshot myCapture;
        synchronized (PeerLocalWriteLock.MONITOR) {
            myCapture = progressSnapshotService.capture(window).snapshot();
        }
        ProgressSummary myBody = myCapture.toProgressSummary();
        // capturedAt = this body's own cutoff, not the real wall-clock capture instant: the cutoff is
        // already clamped into the window (ComparisonWindow#cutoffAt), so it is always <=
        // evaluationInstant below, while the real capture instant generally is NOT once a COMPLETED
        // match is viewed well after endsAt - using it here would spuriously gate as "captured in the
        // future" data that is, in truth, exactly as of the window's own fixed endsAt.
        SnapshotObservation<ProgressSummary> left = SnapshotObservation.available(myBody, myBody.cutoff());

        SnapshotObservation<ProgressSummary> right = resolvePeerMatchSummary(peerId, myId, window);

        // min(now, endsAt), per this feature's own brief - but never less than my own cutoff above:
        // the capture just above reads ProgressSnapshotService's own clock strictly after `now` was
        // read (by this method's own caller, before even calling this method), so on an ACTIVE match
        // my own cutoff can be a few real milliseconds later than `now` itself. Clamping the floor to
        // my own cutoff keeps this an honest "evaluated no earlier than this", never a fabricated
        // instant, and is exactly how PeerComparisonService's own "read the clock last" ordering
        // avoids the identical class of flake for the daily case.
        Instant evaluationInstant = now.isBefore(match.endsAt()) ? now : match.endsAt();
        if (myBody.cutoff().isAfter(evaluationInstant)) {
            evaluationInstant = myBody.cutoff();
        }
        SnapshotComparisonEngine.EvaluationContext context =
                new SnapshotComparisonEngine.EvaluationContext(evaluationInstant, PeerComparisonService.TODAY_FRESHNESS_WINDOW);
        ProgressComparison comparison = SnapshotComparisonEngine.comparePeerProgress(left, right, context);
        return new MatchProgressView(comparison, deriveLeaderVerdict(comparison));
    }

    /**
     * Exact lookup only - unlike {@code PeerComparisonService}'s own "today" fallback, a match's
     * window is already fully, identically known to both sides (the shared {@code startedAt}/
     * {@code endsAt}), so there is no legitimate same-period-but-differently-labelled candidate to
     * fall back to.
     */
    private SnapshotObservation<ProgressSummary> resolvePeerMatchSummary(IdentityId peerId, IdentityId myId,
                                                                            ComparisonWindow matchWindow) {
        Optional<List<SharingScope>> peerConsent;
        try (Connection connection = DatabaseConfig.getConnection()) {
            peerConsent = peerSyncConsentRepository.scopesFor(connection, peerId);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read the peer's consent state.", e);
        }
        if (peerConsent.isEmpty() || !peerConsent.get().contains(SharingScope.MATCH_PARTICIPATION)) {
            return SnapshotObservation.notShared("The peer has not authorized MATCH_PARTICIPATION.");
        }
        ObjectId objectId = SnapshotPublicationService.progressSummaryObjectId(peerId, myId, matchWindow);
        Optional<PeerProgressSummary> found = peerProgressSummaryRepository.findByAuthorAndObjectId(peerId, objectId);
        if (found.isEmpty()) {
            return SnapshotObservation.missing("No matching peer match-progress summary has arrived yet.");
        }
        PeerProgressSummary summary = found.get();
        ProgressSummary body = new ProgressSummary(summary.window(), summary.cutoff(), summary.metrics());
        return SnapshotObservation.available(body, body.cutoff());
    }

    /**
     * "Who is ahead": counts, among metrics both sides actually measured, only the ones whose unit
     * is {@link MetricUnit#COUNT} - an objective "more real work done" measure already classified
     * by the existing metric registry, never a newly invented weighting. {@code AHEAD}/{@code
     * BEHIND} require every counted metric to agree; any split between the two sides is {@code
     * MIXED}; no decisive metric at all is {@code TIED}. Rate metrics (basis points/percent) and
     * {@code problem.solving_seconds} are deliberately excluded: "more seconds spent" or "a higher
     * rate" is not unambiguously "more progress" the way a higher count is.
     */
    static Optional<LeaderVerdict> deriveLeaderVerdict(ProgressComparison comparison) {
        if (comparison.state() != ComparisonState.COMPARABLE && comparison.state() != ComparisonState.DESCRIPTIVE_ONLY) {
            return Optional.empty();
        }
        int leftWins = 0;
        int rightWins = 0;
        for (MetricComparison metric : comparison.metrics()) {
            if (metric.left().isEmpty() || metric.right().isEmpty()) {
                continue;
            }
            MetricValue left = metric.left().get();
            MetricValue right = metric.right().get();
            if (left.unit() != MetricUnit.COUNT || right.unit() != MetricUnit.COUNT) {
                continue;
            }
            if (left.availability() != MetricAvailability.MEASURED || right.availability() != MetricAvailability.MEASURED) {
                continue;
            }
            long difference = left.value() - right.value();
            if (difference > 0) {
                leftWins++;
            } else if (difference < 0) {
                rightWins++;
            }
        }
        if (leftWins > 0 && rightWins > 0) {
            return Optional.of(LeaderVerdict.MIXED);
        }
        if (leftWins > 0) {
            return Optional.of(LeaderVerdict.AHEAD);
        }
        if (rightWins > 0) {
            return Optional.of(LeaderVerdict.BEHIND);
        }
        return Optional.of(LeaderVerdict.TIED);
    }

    private StudyMatch requireMatch(ObjectId matchId) {
        return matchRepository.find(matchId).orElseThrow(() -> new IllegalStateException("No such match: " + matchId));
    }

    private StudyMatch requireActive(ObjectId matchId, Instant now) {
        StudyMatch match = requireMatch(matchId);
        if (match.status() == MatchStatus.ACTIVE) {
            match = completeIfPastEndsAt(matchId, now);
        }
        if (match.status() != MatchStatus.ACTIVE) {
            throw new IllegalStateException("Match " + matchId + " is " + match.status() + ", not ACTIVE.");
        }
        return match;
    }

    /**
     * Synchronized on {@link PeerLocalWriteLock#MONITOR}: this lazy transition is a local write
     * that {@link #compareProgress}/{@link #refreshProgress}/{@link #matchesFor} can all trigger
     * while a connection to the match's own contact is already live, so - exactly like every other
     * local write this feature's own brief already had to account for - it can now run concurrently
     * with that same connection's automatic receive loop and must be serialized against it.
     */
    private StudyMatch completeIfPastEndsAt(ObjectId matchId, Instant now) {
        synchronized (PeerLocalWriteLock.MONITOR) {
            return matchRepository.completeIfPastEndsAt(matchId, now);
        }
    }

    private static ObjectId randomMatchId() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return new ObjectId(buffer.array());
    }
}
