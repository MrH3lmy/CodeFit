package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.comparison.SnapshotComparisonEngine;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ProgressComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.SnapshotObservation;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.WindowKind;
import com.codefit.peer.sync.PeerProgressSummary;
import com.codefit.repository.PeerProgressSummaryRepository;
import com.codefit.repository.PeerSyncConsentRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Orchestration only, for exactly one product slice: "compare today's real progress with one paired
 * peer." Every actual decision - whether two snapshots are comparable, stale, or incompatible - is
 * made by {@link SnapshotComparisonEngine}, never duplicated here. This class's only job is gathering
 * the two real {@link ProgressSummary} observations (mine, captured fresh; the peer's, read from
 * what #184's sync has already received and validated) and the one evaluation context, then handing
 * them to the engine.
 *
 * <h2>Left vs. right</h2>
 * By convention, {@code left} is always this device's own ("my") data and {@code right} is always the
 * named peer's.
 *
 * <h2>Fair partial-period cutoff</h2>
 * A peer's latest accepted DAY summary may have been captured seconds or minutes before the user
 * clicks Compare. Requiring two independently captured partial-day cutoffs to be byte-identical makes
 * the feature practically unusable. When the peer summary is fresh and its elapsed point has already
 * occurred in this device's own day, this service therefore recomputes <em>local</em> evidence through
 * that same elapsed point using {@link ProgressSnapshotService#captureForComparison}. That capture is
 * ephemeral and never replaces the normal persisted/publishable local snapshot. The pure comparison
 * engine still owns every compatibility decision and keeps its strict equal-elapsed guard as a
 * fail-safe.
 *
 * <h2>Peer observation states</h2>
 * The peer's own {@code SnapshotObservation} is built as exactly one of:
 * <ul>
 *   <li>{@link SnapshotObservation#notShared} - the peer's own received {@code CONSENT_REVISION}
 *       (never inferred from the mere presence of cached data - see below) does not currently
 *       authorize {@link SharingScope#DAILY_SUMMARY}. A later revocation naturally produces this too,
 *       since {@code PeerSyncConsentRepository} only ever holds the latest accepted revision;</li>
 *   <li>{@link SnapshotObservation#missing} - the peer does authorize it, but no matching snapshot
 *       has arrived yet (see {@link #resolveTodaysPeerSummary} for the tightly-bounded fallback this
 *       still allows before giving up);</li>
 *   <li>{@link SnapshotObservation#available} - a real, accepted peer snapshot exists.</li>
 * </ul>
 * Consent is always checked <em>first</em> and independently of whether cached snapshot data happens
 * to exist, by design: cached data from before a revocation must never be mistaken for current
 * authorization.
 */
public class PeerComparisonService {

    /**
     * A DAY window is at most ~26 hours wide (DST-adjusted; see {@code ComparisonWindow}'s own
     * bound), so a peer snapshot captured more than 24 hours before the evaluation instant cannot
     * possibly still be describing "today" under any zone - this is the smallest freshness horizon
     * that does not reject a legitimately fresh same-day snapshot, not an arbitrary product policy.
     * No established default existed in {@code SnapshotComparisonEngine}'s own call sites to reuse
     * (there were none in production code before this class), so this is named and documented here
     * rather than left as a magic literal.
     */
    static final Duration TODAY_FRESHNESS_WINDOW = Duration.ofHours(24);

    private final ContactService contactService;
    private final IdentityService identityService;
    private final ProgressSnapshotService progressSnapshotService;
    private final PeerProgressSummaryRepository peerProgressSummaryRepository;
    private final PeerSyncConsentRepository peerSyncConsentRepository;
    private final Clock clock;

    public PeerComparisonService() {
        this(new ContactService(), new IdentityService(), new ProgressSnapshotService(),
                new PeerProgressSummaryRepository(), new PeerSyncConsentRepository(), Clock.systemUTC());
    }

    PeerComparisonService(ContactService contactService, IdentityService identityService,
                           ProgressSnapshotService progressSnapshotService,
                           PeerProgressSummaryRepository peerProgressSummaryRepository,
                           PeerSyncConsentRepository peerSyncConsentRepository, Clock clock) {
        this.contactService = contactService;
        this.identityService = identityService;
        this.progressSnapshotService = progressSnapshotService;
        this.peerProgressSummaryRepository = peerProgressSummaryRepository;
        this.peerSyncConsentRepository = peerSyncConsentRepository;
        this.clock = clock;
    }

    /**
     * Compares this device's own real today's progress with {@code contactId}'s. "Today" is always
     * this device's own {@link ZoneId#systemDefault()} calendar day - the natural meaning of "today"
     * from this user's own point of view, not an arbitrary convention.
     *
     * @throws com.codefit.peer.identity.ContactNotFoundException  no such contact
     * @throws IllegalStateException                               no local identity exists yet
     */
    public ProgressComparison compareTodayWith(long contactId) {
        Contact contact = contactService.requireContact(contactId);
        IdentityId peerId = contact.identityId();
        IdentityId myId = identityService.currentIdentity().map(LocalIdentitySummary::id)
                .orElseThrow(() -> new IllegalStateException("No local identity exists yet."));

        // One logical "now" selects today's window, constrains the fallback candidate, and bounds any
        // historical local comparison cutoff. A later read below becomes the engine evaluation time so
        // it cannot predate the actual local comparison capture.
        Instant comparisonNow = wireTime(clock.instant());
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = comparisonNow.atZone(zone).toLocalDate();
        ComparisonWindow myWindow = ComparisonWindow.day(today, zone);

        SnapshotObservation<ProgressSummary> right =
                resolveTodaysPeerSummary(peerId, myId, myWindow, comparisonNow);

        Instant localCutoff = localComparisonCutoff(myWindow, right, comparisonNow);
        ProgressSnapshotService.ComparisonCapture myCapture =
                progressSnapshotService.captureForComparison(myWindow, localCutoff);
        SnapshotObservation<ProgressSummary> left =
                SnapshotObservation.available(myCapture.summary(), myCapture.capturedAt());

        Instant evaluationInstant = clock.instant();
        SnapshotComparisonEngine.EvaluationContext context =
                new SnapshotComparisonEngine.EvaluationContext(evaluationInstant, TODAY_FRESHNESS_WINDOW);
        return SnapshotComparisonEngine.comparePeerProgress(left, right, context);
    }

    /**
     * Selects only the local data cutoff; it never declares two snapshots compatible. For a fresh
     * available peer summary, use the same elapsed duration from that peer window's start when that
     * elapsed point has already occurred in my window. Otherwise capture through my current time and
     * let {@link SnapshotComparisonEngine} report the real stale/future/complete-partial mismatch.
     */
    private Instant localComparisonCutoff(ComparisonWindow myWindow,
                                          SnapshotObservation<ProgressSummary> right,
                                          Instant now) {
        Optional<ProgressSummary> peerSummary = right.snapshotOptional();
        if (peerSummary.isEmpty()) {
            return myWindow.cutoffAt(now);
        }

        ProgressSummary peer = peerSummary.get();
        Duration age = Duration.between(peer.cutoff(), now);
        if (age.isNegative() || age.compareTo(TODAY_FRESHNESS_WINDOW) > 0) {
            return myWindow.cutoffAt(now);
        }

        Duration peerElapsed = Duration.between(peer.window().start(), peer.cutoff());
        Instant alignedLocalCutoff = myWindow.equalElapsedCutoff(peerElapsed);
        return alignedLocalCutoff.isAfter(now) ? myWindow.cutoffAt(now) : alignedLocalCutoff;
    }

    /**
     * NOT_SHARED if the peer's own latest received consent does not currently authorize {@link
     * SharingScope#DAILY_SUMMARY}; otherwise an exact object/window lookup for today's window as
     * computed in <em>this device's own zone</em>, falling back - only if that exact lookup misses -
     * to the single already-cached peer summary (if any) whose own declared DAY window genuinely
     * contains the evaluation instant right now. That fallback is deliberately narrow: it exists only
     * to let the engine itself diagnose a real same-period incompatibility (the peer published under
     * a different zone string, or a UTC-interval mismatch under an otherwise-matching local label) -
     * never to resurrect an old, already-elapsed day's snapshot merely because it is the newest row
     * on file. If no such candidate exists either, MISSING.
     */
    private SnapshotObservation<ProgressSummary> resolveTodaysPeerSummary(IdentityId peerId, IdentityId myId,
                                                                            ComparisonWindow myWindow, Instant now) {
        Optional<List<SharingScope>> peerConsent;
        try (Connection connection = DatabaseConfig.getConnection()) {
            peerConsent = peerSyncConsentRepository.scopesFor(connection, peerId);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read the peer's consent state.", e);
        }
        if (peerConsent.isEmpty() || !peerConsent.get().contains(SharingScope.DAILY_SUMMARY)) {
            return SnapshotObservation.notShared("The peer has not authorized DAILY_SUMMARY.");
        }

        ObjectId exactObjectId = SnapshotPublicationService.progressSummaryObjectId(peerId, myId, myWindow);
        Optional<PeerProgressSummary> exact = peerProgressSummaryRepository.findByAuthorAndObjectId(peerId, exactObjectId);
        Optional<PeerProgressSummary> candidate = exact.isPresent() ? exact
                : plausibleSameDayFallback(peerId, now);

        if (candidate.isEmpty()) {
            return SnapshotObservation.missing("No matching peer progress summary has arrived yet.");
        }
        PeerProgressSummary summary = candidate.get();
        ProgressSummary body = new ProgressSummary(summary.window(), summary.cutoff(), summary.metrics());
        return SnapshotObservation.available(body, summary.receivedAt());
    }

    /**
     * The only fallback this class ever considers: a cached DAY summary from this exact peer whose
     * own declared window - as THEY published it, in whatever zone they used - contains right now.
     * That is the authoritative, zone-agnostic definition of "their today": a window that does not
     * contain the current instant has, by construction, already ended (or not yet started), so a
     * stale or future window can never qualify. At most one legitimate candidate is expected in
     * practice; if more than one somehow qualifies, the one with the latest start is preferred.
     */
    private Optional<PeerProgressSummary> plausibleSameDayFallback(IdentityId peerId, Instant now) {
        return peerProgressSummaryRepository.findByAuthor(peerId).stream()
                .filter(summary -> summary.window().kind() == WindowKind.DAY)
                .filter(summary -> summary.window().contains(now))
                .max(Comparator.comparing(summary -> summary.window().start()));
    }

    private static Instant wireTime(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }
}
