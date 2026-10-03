package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.AcceptanceVerdict;
import com.codefit.peer.protocol.AuthorReplayState;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeAcceptancePolicy;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.repository.InterviewMockRepository;
import com.codefit.repository.LocalPreparationCheckpointRepository;
import com.codefit.repository.LocalProgressSnapshotRepository;
import com.codefit.repository.PreparationSnapshotWireStateRepository;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #183's grant-enforcement and signing boundary: a contact's current #181 sharing grant must be
 * checked before any snapshot is captured/encoded/signed, two differently-granted contacts must never
 * be able to collide onto the same signed payload, and the signature must genuinely cover the whole
 * permitted body.
 *
 * <p>{@link SnapshotPublicationService#publishProgressSummary}/{@code publishPreparationSnapshot} take
 * no {@code Instant} parameter at all — every test here builds its own service via
 * {@link #publicationServiceAt} with a {@link Clock#fixed} standing in for "real current time," so a
 * test can simulate publishing an old, historical {@link ComparisonWindow} while trusted "now" is
 * genuinely later, exactly the distinction the trusted-clock fix depends on.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SnapshotPublicationServiceTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final ContactService contactService = new ContactService();
    private final IdentityService identityService = new IdentityService();
    private UnlockedIdentity identity;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        identityService.createIdentity("vault-pass".toCharArray(), BASE);
        identityService.resumeSharingAfterReview();
        identity = identityService.unlock("vault-pass".toCharArray());
    }

    /** A fresh publication service (and its own fresh sub-services) whose trusted clock is fixed at {@code now}. */
    private SnapshotPublicationService publicationServiceAt(Instant now) {
        return publicationServiceWithClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    private SnapshotPublicationService publicationServiceWithClock(Clock clock) {
        ProgressSnapshotService progressSnapshotService = new ProgressSnapshotService(new ReviewHistoryRepository(),
                new ProblemAttemptRepository(), new InterviewMockRepository(),
                new LocalProgressSnapshotRepository(), clock);
        PreparationSnapshotCaptureService preparationSnapshotCaptureService = new PreparationSnapshotCaptureService(
                new InterviewReadinessService(), new InterviewProfileService(), new LocalPreparationCheckpointRepository(), clock);
        return new SnapshotPublicationService(contactService, progressSnapshotService, preparationSnapshotCaptureService,
                new PreparationSnapshotWireStateRepository(), clock);
    }

    /** A {@link Clock} whose reported instant can be advanced mid-test, so one {@link SnapshotPublicationService}
     * instance (and its one, process-lifetime sequence counter) can be reused across two publishes at different
     * simulated "now" values - exactly how one real, long-running app instance behaves. */
    private static final class AdjustableClock extends Clock {
        private volatile Instant current;

        AdjustableClock(Instant current) {
            this.current = current;
        }

        void set(Instant instant) {
            this.current = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private static IdentityKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new IdentityKey(bytes);
    }

    private Contact pairedContact(int keyFill, String name, Instant now) {
        Contact pending = contactService.registerPendingContact(key(keyFill), name, now);
        return contactService.acceptInvitation(pending.id(), now);
    }

    @Test
    void unauthorizedContactCannotPublishAnything() {
        Contact contact = pairedContact(1, "Ada", BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(BASE.plusSeconds(1)).publishProgressSummary(contact.id(), identity, 1, window));
    }

    @Test
    void grantedContactCanPublishAndTheSignatureVerifies() {
        Contact contact = pairedContact(2, "Ben", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        SignedEnvelope envelope = publicationServiceAt(now).publishProgressSummary(contact.id(), identity, 1, window);

        assertTrue(envelope.verifySignature(), "a freshly signed envelope must verify");
        assertTrue(envelope.header().audience().includes(contact.identityId()), "envelope is addressed to this contact");
        assertTrue(envelope.body() instanceof ProgressSummary);
    }

    @Test
    void modifyingASignedFieldBreaksVerification() {
        Contact contact = pairedContact(3, "Cara", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        SignedEnvelope original = publicationServiceAt(now).publishProgressSummary(contact.id(), identity, 1, window);

        EnvelopeHeader tamperedHeader = new EnvelopeHeader(original.header().minorVersion(), original.header().author(),
                original.header().objectId(), original.header().epoch(), original.header().sequence(),
                original.header().revision() + 1, original.header().createdAt(), original.header().expiresAt(),
                original.header().audience());
        SignedEnvelope tampered = new SignedEnvelope(new Envelope(tamperedHeader, original.body()), original.signature());

        assertFalse(tampered.verifySignature(), "changing any signed field must break verification");
    }

    @Test
    void aSnapshotSignedForOneContactIsNotAddressedToAnother() {
        Contact contactA = pairedContact(4, "Dee", BASE);
        Contact contactB = pairedContact(5, "Eve", BASE);
        PermissionGrant grant = new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false);
        contactService.updatePermissions(contactA.id(), grant, BASE);
        contactService.updatePermissions(contactB.id(), grant, BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();
        SnapshotPublicationService publicationService = publicationServiceAt(now);

        SignedEnvelope forA = publicationService.publishProgressSummary(contactA.id(), identity, 1, window);
        SignedEnvelope forB = publicationService.publishProgressSummary(contactB.id(), identity, 1, window);

        assertFalse(forA.header().audience().includes(contactB.identityId()), "A's envelope must not be addressed to B");
        assertFalse(forB.header().audience().includes(contactA.identityId()), "B's envelope must not be addressed to A");
        assertNotEquals(forA.header().objectId(), forB.header().objectId(),
                "different recipients of the same window must never share one object id / revision stream");
    }

    @Test
    void differentGrantsProduceDifferentPermittedProjectionsOfTheSameLocalState() {
        Contact daily = pairedContact(6, "Finn", BASE);
        Contact weekly = pairedContact(7, "Gia", BASE);
        contactService.updatePermissions(daily.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        contactService.updatePermissions(weekly.id(),
                new PermissionGrant(List.of(SharingScope.WEEKLY_SUMMARY), null, null, false), BASE);

        ComparisonWindow dayWindow = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        ComparisonWindow weekWindow = ComparisonWindow.week(LocalDate.of(2026, 1, 12), UTC, java.time.DayOfWeek.MONDAY);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();
        SnapshotPublicationService publicationService = publicationServiceAt(now);

        // The daily-only contact may receive a day window but not a week window, and vice versa -
        // each contact's own grant decides exactly which projection of the same underlying evidence
        // they may ever be given, enforced before any capture/signing happens.
        SignedEnvelope dailyEnvelope = publicationService.publishProgressSummary(daily.id(), identity, 1, dayWindow);
        assertTrue(dailyEnvelope.verifySignature());
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(daily.id(), identity, 1, weekWindow));

        SignedEnvelope weeklyEnvelope = publicationService.publishProgressSummary(weekly.id(), identity, 1, weekWindow);
        assertTrue(weeklyEnvelope.verifySignature());
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(weekly.id(), identity, 1, dayWindow));
    }

    @Test
    void grantWithNoHistoricalWindowNeverSharesEvidenceFromBeforeTheGrant() {
        Contact contact = pairedContact(8, "Hugo", BASE.plusSeconds(10_000));
        Instant grantedAt = BASE.plusSeconds(10_000);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), grantedAt);

        ComparisonWindow beforeGrant = ComparisonWindow.day(LocalDate.ofInstant(BASE, UTC), UTC);
        Instant now = grantedAt.plus(Duration.ofDays(1));

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(now).publishProgressSummary(contact.id(), identity, 1, beforeGrant),
                "a window entirely before the grant was made must never be published, even once authorized going forward");
    }

    @Test
    void blockedContactCannotReceiveANewSnapshotEvenWithAPriorGrant() {
        Contact contact = pairedContact(9, "Ivy", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        contactService.block(contact.id(), false, BASE.plusSeconds(1));

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(now).publishProgressSummary(contact.id(), identity, 1, window));
    }

    @Test
    void removedContactCannotReceiveANewSnapshotEvenWithAPriorGrant() {
        Contact contact = pairedContact(10, "Jon", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        contactService.remove(contact.id(), false, BASE.plusSeconds(1));

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(now).publishProgressSummary(contact.id(), identity, 1, window));
    }

    @Test
    void expiredGrantCannotReceiveANewSnapshot() {
        Contact contact = pairedContact(11, "Kim", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, BASE.plusSeconds(100), false), BASE);

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant afterExpiry = BASE.plusSeconds(500);

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(afterExpiry).publishProgressSummary(contact.id(), identity, 1, window));
    }

    @Test
    void preparationSnapshotIsAlsoGatedByItsOwnScopeAndSignsCleanly() {
        Contact contact = pairedContact(12, "Lia", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.PREPARATION_SNAPSHOT), null, null, false), BASE);
        String profileId = new InterviewProfileService().getRevolutJavaProfile().getId();
        Instant now = BASE.plusSeconds(100);
        SnapshotPublicationService publicationService = publicationServiceAt(now);

        SignedEnvelope envelope = publicationService.publishPreparationSnapshot(contact.id(), identity, 1, profileId);

        assertTrue(envelope.verifySignature());
        assertTrue(envelope.body() instanceof PreparationSnapshot);

        Contact ungranted = pairedContact(13, "Mo", BASE);
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishPreparationSnapshot(ungranted.id(), identity, 1, profileId));
    }

    // --- trusted-time regression tests (review fix) ---

    @Test
    void anExpiredGrantStaysExpiredEvenWhenPublishingAnOldHistoricalWindow() {
        // The grant expired at BASE+100s. Trusted "now" is BASE+10 real days later (long after expiry).
        // The WINDOW being published is from right at BASE - entirely within what the grant would have
        // allowed while it was still active. A caller cannot revive the expired grant just because the
        // thing being published happens to be old: authorization is evaluated at real current time only.
        Contact contact = pairedContact(14, "Noor", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, BASE.plusSeconds(100), false), BASE);

        ComparisonWindow oldWindow = ComparisonWindow.day(LocalDate.ofInstant(BASE, UTC), UTC);
        Instant trustedNow = BASE.plus(Duration.ofDays(10));

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(trustedNow).publishProgressSummary(contact.id(), identity, 1, oldWindow),
                "an expired grant must stay refused no matter how old the window being requested is");
    }

    @Test
    void historicalWindowBoundaryIsCalculatedRelativeToActualCurrentAuthorizationTime() {
        // historicalWindowDays=5: "share the last 5 days of history, as of whenever this is checked."
        // The earliest-allowed instant must slide forward as real time passes, not stay pinned to
        // whatever moment the grant was created or first checked.
        Contact contact = pairedContact(15, "Omar", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 5, null, false), BASE);

        ComparisonWindow window = ComparisonWindow.day(LocalDate.ofInstant(BASE, UTC), UTC);

        // Checked 2 days after the grant: earliest allowed = (BASE+2d) - 5d = BASE-3d. The window
        // (starting at BASE) is within that range.
        Instant soonAfterGrant = BASE.plus(Duration.ofDays(2));
        SignedEnvelope envelope = publicationServiceAt(soonAfterGrant).publishProgressSummary(contact.id(), identity, 1, window);
        assertTrue(envelope.verifySignature());

        // Checked 10 days after the grant: earliest allowed = (BASE+10d) - 5d = BASE+5d. The SAME
        // window (starting at BASE) is now before that boundary - the sliding window moved past it.
        Instant longAfterGrant = BASE.plus(Duration.ofDays(10));
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(longAfterGrant).publishProgressSummary(contact.id(), identity, 1, window),
                "the same window must become unshareable once the sliding 5-day lookback has moved past it");
    }

    @Test
    void envelopeCreatedAtReflectsTrustedCurrentTimeNotTheHistoricalPeriodCutoff() {
        Contact contact = pairedContact(16, "Priya", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 365, null, false), BASE);

        LocalDate historicalDay = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(historicalDay, UTC);
        // Trusted "now" is two weeks after the window closes.
        Instant trustedNow = historicalDay.plusDays(14).atTime(10, 0).atZone(UTC).toInstant();

        SignedEnvelope envelope = publicationServiceAt(trustedNow).publishProgressSummary(contact.id(), identity, 1, window);

        assertEquals(trustedNow, envelope.header().createdAt(), "createdAt must be the real moment of publication, not the window's own cutoff");
        assertNotEquals(window.end(), envelope.header().createdAt());
    }

    @Test
    void receiverAcceptsTheNewerSameSecondRevisionRatherThanRejectingItAsStale() {
        Contact contact = pairedContact(17, "Quinn", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant firstInstant = LocalDate.of(2026, 1, 15).atTime(23, 0, 0, 100_000_000).atZone(UTC).toInstant();
        Instant secondInstant = LocalDate.of(2026, 1, 15).atTime(23, 0, 0, 900_000_000).atZone(UTC).toInstant();
        assertEquals(firstInstant.getEpochSecond(), secondInstant.getEpochSecond(), "sanity check: same whole epoch second");

        // One service instance (one process-lifetime sequence counter) across both publishes, exactly
        // like one real running app - only the trusted clock's reported instant advances between them.
        // No fixtures need to change either: the cutoff instant itself (encoded in the signed
        // ProgressSummary body) already differs between firstInstant and secondInstant, which is
        // enough on its own to make the two bodies - and therefore their revisions - differ.
        AdjustableClock clock = new AdjustableClock(firstInstant);
        SnapshotPublicationService publicationService = publicationServiceWithClock(clock);
        SignedEnvelope first = publicationService.publishProgressSummary(contact.id(), identity, 1, window);
        clock.set(secondInstant);
        SignedEnvelope second = publicationService.publishProgressSummary(contact.id(), identity, 1, window);

        assertNotEquals(first.header().revision(), second.header().revision());
        assertTrue(second.header().revision() > first.header().revision());

        AuthorReplayState receiverState = new AuthorReplayState(identity.publicKey());
        EnvelopeAcceptancePolicy receiverPolicy = new EnvelopeAcceptancePolicy(contact.identityId());
        AcceptanceVerdict firstVerdict = receiverPolicy.evaluate(first, receiverState, secondInstant);
        assertTrue(firstVerdict.accepted(), "the first envelope: " + firstVerdict);
        AcceptanceVerdict secondVerdict = receiverPolicy.evaluate(second, receiverState, secondInstant);
        assertTrue(secondVerdict.accepted(), "the newer same-second revision must be accepted, not rejected as stale: " + secondVerdict);
    }

    @Test
    void permissionMutationAndPublicationCriticalSectionsAreMutuallyExclusive() throws InterruptedException {
        // SnapshotPublicationService's final authorization check + signing, and ContactService's own
        // permission mutations, synchronize on the exact same monitor (ContactService.PERMISSION_LOCK).
        // This proves that sharing directly: while a thread holds the lock (standing in for a publish
        // call's critical section), a concurrent block() cannot complete, and once the lock is released,
        // the revocation is immediately visible to the next authorization check.
        Contact contact = pairedContact(18, "Rin", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);

        CountDownLatch holdingLock = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (ContactService.PERMISSION_LOCK) {
                holdingLock.countDown();
                try {
                    releaseLock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        assertTrue(holdingLock.await(2, TimeUnit.SECONDS), "holder thread must acquire the lock");

        AtomicBoolean blockCompleted = new AtomicBoolean(false);
        Thread blocker = new Thread(() -> {
            contactService.block(contact.id(), false, BASE.plusSeconds(1));
            blockCompleted.set(true);
        });
        blocker.start();
        blocker.join(300);
        assertFalse(blockCompleted.get(), "block() must wait for the in-flight critical section to finish");

        releaseLock.countDown();
        blocker.join(2000);
        assertTrue(blockCompleted.get(), "block() completes once the lock is released");
        holder.join(2000);

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationServiceAt(now).publishProgressSummary(contact.id(), identity, 1, window),
                "a publish started after the revocation committed must observe the revoked grant");
    }
}
