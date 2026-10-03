package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MessageBody;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.WindowKind;
import com.codefit.peer.snapshot.LocalProgressSnapshot;
import com.codefit.repository.PreparationSnapshotWireStateRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The privacy/trust boundary for #183: the <em>only</em> place a {@link ProgressSummary} or
 * {@link PreparationSnapshot} is turned into a signed envelope for a specific contact. Reuses #181's
 * existing grant model ({@code ContactService.isAuthorizedToPublish}/{@code historicalWindowStart})
 * rather than a second privacy system, and checks it <strong>before</strong> any encoding or signing —
 * never after, never "fix it in the UI". A contact who is unauthorized, blocked, removed, or whose
 * grant does not reach back far enough gets a refusal, never a signed envelope.
 *
 * <p>Per-recipient projection happens by construction, not by redaction: the body handed to
 * {@link #signFor} is built fresh for exactly the one contact this call is for, so two contacts with
 * different grants can never end up sharing one over-broad signed payload — each gets its own
 * envelope, with its own {@link ObjectId} (folding in the recipient, the same way the existing
 * {@code CONSENT_REVISION} object id folds in its recipient) and its own signature.
 *
 * <h2>Trusted time</h2>
 * Every authorization decision and every envelope {@code createdAt} uses this service's own
 * {@link Clock} ({@link Clock#systemUTC()} in production) — never an {@code Instant} the caller
 * supplies. An earlier version of both publish methods took an {@code Instant now} parameter and fed
 * it straight into {@code ContactService.historicalWindowStart}'s expiry/boundary checks: a caller
 * could revive an already-expired grant simply by passing a timestamp from before it expired. A
 * window or profile being historical is a property of <em>what is being published</em>
 * ({@link ComparisonWindow#start()}, a checkpoint's {@code capturedAt}); whether the recipient is
 * authorized <em>right now</em> is a completely different question that must always use real current
 * time.
 *
 * <h2>Revocation race</h2>
 * The final authorization re-check and the signing itself happen inside {@code synchronized
 * (ContactService.PERMISSION_LOCK)} — the same monitor {@code ContactService.updatePermissions}/
 * {@code block}/{@code remove} already hold for their own read-compare-write — so a publish that is
 * mid-flight when a revocation commits cannot complete using the grant that revocation just revoked:
 * either this method's lock acquisition happens-before the revocation's (and the revocation then wins,
 * since the DB write it does is itself inside the same lock and this method re-reads from the DB after
 * acquiring), or the revocation's commit happens-before this method enters the lock (and the re-check
 * inside it sees the revoked state). The expensive local evidence aggregation happens <em>before</em>
 * the lock is taken, so this never holds {@code PERMISSION_LOCK} across a slow DB scan.
 */
public class SnapshotPublicationService {

    /** How long a published progress summary stays valid on the wire before a receiver drops it. */
    private static final Duration PROGRESS_SUMMARY_LIFETIME = Duration.ofDays(90);
    /** Readiness changes slower than day-to-day activity, so preparation snapshots get a longer wire lifetime. */
    private static final Duration PREPARATION_SNAPSHOT_LIFETIME = Duration.ofDays(180);

    private final ContactService contactService;
    private final ProgressSnapshotService progressSnapshotService;
    private final PreparationSnapshotCaptureService preparationSnapshotCaptureService;
    private final PreparationSnapshotWireStateRepository preparationWireStateRepository;
    private final Clock clock;
    private final PublishedEnvelopeCache envelopeCache = new PublishedEnvelopeCache();

    public SnapshotPublicationService() {
        this(new ContactService(), new ProgressSnapshotService(), new PreparationSnapshotCaptureService(),
                new PreparationSnapshotWireStateRepository(), Clock.systemUTC());
    }

    SnapshotPublicationService(ContactService contactService, ProgressSnapshotService progressSnapshotService,
                                PreparationSnapshotCaptureService preparationSnapshotCaptureService,
                                PreparationSnapshotWireStateRepository preparationWireStateRepository, Clock clock) {
        this.contactService = contactService;
        this.progressSnapshotService = progressSnapshotService;
        this.preparationSnapshotCaptureService = preparationSnapshotCaptureService;
        this.preparationWireStateRepository = preparationWireStateRepository;
        this.clock = clock;
    }

    /**
     * Captures (and durably records, for future historical use) {@code window}'s progress snapshot
     * from real local evidence, then signs the exact permitted projection for {@code contactId}.
     *
     * @throws NotAuthorizedToPublishException the contact is not paired, has not granted the
     *                                          day/week scope this window needs, or its grant does not
     *                                          reach back to {@code window.start()}
     */
    public SignedEnvelope publishProgressSummary(long contactId, UnlockedIdentity identity, long writerEpoch,
                                                  ComparisonWindow window) {
        Contact contact = contactService.requireContact(contactId);
        SharingScope scope = window.kind() == WindowKind.DAY ? SharingScope.DAILY_SUMMARY : SharingScope.WEEKLY_SUMMARY;

        // A cheap fail-fast check before the expensive aggregation below - not the authoritative
        // decision (that happens again, freshly, inside the lock right before signing), just avoids
        // running a real DB scan (and, for a nonsensical request such as a not-yet-started window,
        // hitting capture-time validation) for a contact that was never going to be authorized anyway.
        requireAuthorized(contactId, scope, window.start(), clock.instant());

        // The expensive evidence aggregation runs outside PERMISSION_LOCK, deliberately: it never
        // needs to coordinate with a permission mutation, only the authorization decision below does.
        LocalProgressSnapshot captured = progressSnapshotService.capture(window).snapshot();
        ProgressSummary body = captured.toProgressSummary();
        ObjectId objectId = deriveObjectId("CodeFit-Progress-Summary-v1", identity.publicKey().id(), contact.identityId(),
                windowIdentityBytes(window));

        synchronized (ContactService.PERMISSION_LOCK) {
            Instant now = clock.instant();
            requireAuthorized(contactId, scope, window.start(), now);
            return signFor(identity, writerEpoch, contact.identityId(), objectId, body, captured.revision(), now,
                    PROGRESS_SUMMARY_LIFETIME);
        }
    }

    /**
     * Captures (and durably records) {@code profileId}'s readiness checkpoint for right now from the
     * real, unmodified readiness engine, then signs the exact permitted projection for {@code contactId}.
     *
     * @throws NotAuthorizedToPublishException the contact is not paired, has not granted
     *                                          {@link SharingScope#PREPARATION_SNAPSHOT}, or its grant
     *                                          does not reach back to this capture
     * @throws IllegalArgumentException        {@code profileId} does not resolve to a known profile
     */
    public SignedEnvelope publishPreparationSnapshot(long contactId, UnlockedIdentity identity, long writerEpoch,
                                                       String profileId) {
        Contact contact = contactService.requireContact(contactId);

        // Same fail-fast rationale as publishProgressSummary: not the authoritative decision, just
        // avoids running the readiness engine at all for a contact that was never going to be granted.
        requireGranted(contactId, SharingScope.PREPARATION_SNAPSHOT, clock.instant());

        PreparationSnapshotCaptureService.Capture captured = preparationSnapshotCaptureService.capture(profileId)
                .orElseThrow(() -> new IllegalArgumentException("No known preparation profile '" + profileId + "'."));
        PreparationSnapshot body = captured.checkpoint().snapshot();
        ObjectId objectId = deriveObjectId("CodeFit-Preparation-Snapshot-v1", identity.publicKey().id(), contact.identityId(),
                profileId.getBytes(StandardCharsets.UTF_8));

        synchronized (ContactService.PERMISSION_LOCK) {
            Instant now = clock.instant();
            Instant earliestAllowed = requireGranted(contactId, SharingScope.PREPARATION_SNAPSHOT, now);
            if (body.capturedAt().isBefore(earliestAllowed)) {
                throw NotAuthorizedToPublishException.beforeHistoricalWindow(contactId, SharingScope.PREPARATION_SNAPSHOT,
                        body.capturedAt(), earliestAllowed);
            }
            long revision = preparationWireStateRepository.nextRevision(objectId, fingerprint(body));
            return signFor(identity, writerEpoch, contact.identityId(), objectId, body, revision, now, PREPARATION_SNAPSHOT_LIFETIME);
        }
    }

    /** @return the earliest instant this contact's {@code scope} grant allows sharing, as of {@code now} */
    private Instant requireGranted(long contactId, SharingScope scope, Instant now) {
        return contactService.historicalWindowStart(contactId, scope, now)
                .orElseThrow(() -> NotAuthorizedToPublishException.notGranted(contactId, scope));
    }

    private void requireAuthorized(long contactId, SharingScope scope, Instant windowStart, Instant now) {
        Instant earliestAllowed = requireGranted(contactId, scope, now);
        if (windowStart.isBefore(earliestAllowed)) {
            throw NotAuthorizedToPublishException.beforeHistoricalWindow(contactId, scope, windowStart, earliestAllowed);
        }
    }

    private SignedEnvelope signFor(UnlockedIdentity identity, long writerEpoch, IdentityId recipient, ObjectId objectId,
                                    MessageBody body, long revision, Instant now, Duration lifetime) {
        Instant createdAt = Instant.ofEpochMilli(now.toEpochMilli());
        return envelopeCache.get(identity, writerEpoch, recipient, objectId, body, () -> {
            EnvelopeHeader header = new EnvelopeHeader(0, identity.publicKey(), objectId, writerEpoch,
                    envelopeCache.nextSequence(writerEpoch), revision, createdAt, createdAt.plus(lifetime),
                    Audience.direct(List.of(recipient)));
            Envelope envelope = new Envelope(header, body);
            byte[] signature = identity.sign(envelope.signingBytes());
            return new SignedEnvelope(envelope, signature);
        });
    }

    private static byte[] fingerprint(MessageBody body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body.encodeBody());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform.", e);
        }
    }

    private static byte[] windowIdentityBytes(ComparisonWindow window) {
        byte[] zoneBytes = window.zoneId().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + 8 + zoneBytes.length + 1];
        out[0] = (byte) window.kind().code();
        long epochDay = window.localStartDate().toEpochDay();
        for (int i = 0; i < 8; i++) {
            out[1 + i] = (byte) (epochDay >>> (8 * (7 - i)));
        }
        System.arraycopy(zoneBytes, 0, out, 9, zoneBytes.length);
        out[out.length - 1] = window.weekStart() == null ? 0 : (byte) window.weekStart().getValue();
        return out;
    }

    private static ObjectId deriveObjectId(String context, IdentityId authorId, IdentityId recipientId, byte[] extra) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((context + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(authorId.bytes());
            digest.update(recipientId.bytes());
            digest.update(extra);
            return new ObjectId(Arrays.copyOf(digest.digest(), ObjectId.LENGTH));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform.", e);
        }
    }

    /**
     * Caches the one signed envelope built per (writer epoch, recipient, object id) <em>within this
     * process's lifetime</em>, rebuilding only when the underlying body actually changed - the same
     * idempotent-resend pattern {@code com.codefit.peer.transport.LocalBindingEnvelopeCache} already
     * established, so resending an unchanged snapshot within one run is always exactly as safe as never
     * resending it (the receiver's {@code EnvelopeAcceptancePolicy} reports it as the idempotent
     * {@code DUPLICATE}, never {@code STALE_REVISION}). This is a performance cache only, not the
     * source of revision truth: Ed25519 signatures are deterministic, so recomputing one for an
     * unchanged body after a restart (an empty cache) yields byte-identical output anyway, and the
     * revision itself always comes from the persisted, monotonic counters in
     * {@code LocalProgressSnapshotRepository}/{@code PreparationSnapshotWireStateRepository}, which
     * survive a restart.
     */
    private static final class PublishedEnvelopeCache {
        private record Key(IdentityId recipient, ObjectId objectId) {
        }

        private final Map<Long, Map<Key, SignedEnvelope>> byEpoch = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

        long nextSequence(long epoch) {
            return sequenceCounters.computeIfAbsent(epoch, e -> new AtomicLong(0)).incrementAndGet();
        }

        SignedEnvelope get(UnlockedIdentity identity, long epoch, IdentityId recipient, ObjectId objectId,
                           MessageBody body, java.util.function.Supplier<SignedEnvelope> builder) {
            Map<Key, SignedEnvelope> forEpoch = byEpoch.computeIfAbsent(epoch, e -> new ConcurrentHashMap<>());
            Key key = new Key(recipient, objectId);
            return forEpoch.compute(key, (k, cached) -> {
                if (cached != null && cached.body().equals(body) && cached.header().author().equals(identity.publicKey())) {
                    return cached;
                }
                return builder.get();
            });
        }
    }

    /** Why a snapshot publication was refused - thrown before any encoding or signing happens. */
    public static final class NotAuthorizedToPublishException extends RuntimeException {
        private NotAuthorizedToPublishException(String message) {
            super(message);
        }

        static NotAuthorizedToPublishException notGranted(long contactId, SharingScope scope) {
            return new NotAuthorizedToPublishException("Contact " + contactId + " has not granted " + scope
                    + " (not paired, blocked, removed, or the scope was never granted).");
        }

        static NotAuthorizedToPublishException beforeHistoricalWindow(long contactId, SharingScope scope,
                                                                        Instant requested, Instant earliestAllowed) {
            return new NotAuthorizedToPublishException("Contact " + contactId + "'s " + scope + " grant allows history "
                    + "from " + earliestAllowed + " onward; " + requested + " is earlier than that.");
        }
    }
}
