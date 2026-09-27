package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.IllegalContactStateException;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.identity.crypto.EncryptedSecret;
import com.codefit.peer.identity.crypto.IdentityBackupCodec;
import com.codefit.peer.identity.crypto.IdentityBackupPayload;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.identity.crypto.PassphraseCipher;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.repository.ConsentChangeEventRepository;
import com.codefit.repository.ContactPermissionRepository;
import com.codefit.repository.ContactRepository;
import com.codefit.repository.IdentityKeyRotationRepository;
import com.codefit.repository.PeerIdentityRepository;
import com.codefit.repository.PeerIdentityRow;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.security.KeyPair;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers a second-round #192 review finding: {@code downgradeAllPairedContactsForIdentityReset} used
 * to release {@code ContactService.PERMISSION_LOCK} as soon as it returned, not when the transaction
 * that calls it actually committed. Since an uncommitted write on one JDBC connection is invisible to
 * a query on a different one, a concurrent {@code updatePermissions} call could slip into that window,
 * read the pre-downgrade PAIRED/revision-1 state, and commit a fresh grant <em>after</em> recovery
 * finished — silently reactivating access for a contact the reset/import had just downgraded. The fix
 * holds {@code PERMISSION_LOCK} for the whole recovery transaction (through commit or rollback), which
 * this test proves by staging the exact interleaving the review reproduced: it lets the downgrade's
 * writes land on the recovery connection, then gives a concurrent permission read up to a second to
 * either observe stale pre-downgrade state (the old bug) or block behind the fix's wider lock — either
 * way, the row after both operations finish must never carry a stale, reactivatable grant.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class IdentityRecoveryPermissionRaceTest {
    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    private final IdentityService identityService = new IdentityService();
    private final ContactService contactService = new ContactService();

    @BeforeEach
    void resetPeerIdentityTables() {
        PeerIdentityTestTables.resetAll();
        identityService.createIdentity("vault-pass".toCharArray(), BASE);
    }

    @Test
    void resetDoesNotLeaveAStaleGrantThatSurvivesUnderTheDowngradedContact() throws Exception {
        checkRecovery(false);
    }

    @Test
    void differentIdentityImportDoesNotLeaveAStaleGrantThatSurvivesUnderTheDowngradedContact() throws Exception {
        checkRecovery(true);
    }

    private void checkRecovery(boolean restore) throws Exception {
        Contact peer = contactService.registerPendingContact(sampleContactKey((byte) 55), "Peer", BASE);
        contactService.acceptInvitation(peer.id(), BASE.plusSeconds(1));
        PermissionGrant grant = new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false);
        contactService.updatePermissions(peer.id(), grant, BASE.plusSeconds(2));

        CountDownLatch downgradedButNotCommitted = new CountDownLatch(1);
        CountDownLatch allowIdentityWrite = new CountDownLatch(1);
        CountDownLatch permissionRead = new CountDownLatch(1);
        CountDownLatch recoveryFinished = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);

        PeerIdentityRepository stagedIdentityRepository = new PeerIdentityRepository() {
            @Override
            public void replace(Connection connection, PeerIdentityRow row) {
                // By the time this runs, the caller has already downgraded contacts on this same
                // transaction's connection, but nothing has committed yet.
                downgradedButNotCommitted.countDown();
                await(allowIdentityWrite);
                super.replace(connection, row);
            }
        };
        ContactPermissionRepository stagedPermissionRepository = new ContactPermissionRepository() {
            @Override
            public Optional<ContactPermission> find(long contactId) {
                Optional<ContactPermission> snapshot = super.find(contactId);
                permissionRead.countDown();
                await(recoveryFinished);
                return snapshot;
            }
        };
        ContactService concurrentContactService = new ContactService(new ContactRepository(), stagedPermissionRepository,
                new ConsentChangeEventRepository(), new PeerIdentityRepository());
        IdentityService recoveryIdentityService = new IdentityService(stagedIdentityRepository,
                new IdentityKeyRotationRepository(), contactService);
        IdentityBackupService recoveryBackupService = new IdentityBackupService(stagedIdentityRepository, contactService);
        byte[] backupForAnotherIdentity = backupForABrandNewIdentity();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var recovery = executor.submit(() -> {
                try {
                    return restore
                            ? recoveryBackupService.importBackup(backupForAnotherIdentity, "backup-pass".toCharArray(),
                                    "new-vault-pass".toCharArray(), BASE.plusSeconds(10))
                            : recoveryIdentityService.resetIdentityWithoutContinuity("new-vault-pass".toCharArray(), BASE.plusSeconds(10));
                } finally {
                    recoveryFinished.countDown();
                }
            });
            await(downgradedButNotCommitted);
            var concurrentUpdate = executor.submit(() -> {
                updateStarted.countDown();
                try {
                    return Optional.of(concurrentContactService.updatePermissions(peer.id(), grant, BASE.plusSeconds(11)));
                } catch (IllegalContactStateException rejectedAfterRecovery) {
                    return Optional.<ContactPermission>empty();
                }
            });
            try {
                await(updateStarted);
                // A fix that holds PERMISSION_LOCK through commit blocks this read until recovery
                // finishes, so timing out here is the FIXED behaviour, not a test failure.
                permissionRead.await(1, TimeUnit.SECONDS);
            } finally {
                allowIdentityWrite.countDown();
            }
            recovery.get(10, TimeUnit.SECONDS);
            concurrentUpdate.get(10, TimeUnit.SECONDS);
        } finally {
            allowIdentityWrite.countDown();
            recoveryFinished.countDown();
            executor.shutdown();
        }

        assertEquals(TrustState.PENDING, contactService.requireContact(peer.id()).trustState());
        ContactPermission afterRecovery = contactService.permissionsFor(peer.id()).orElseThrow();
        assertTrue(afterRecovery.scopes().isEmpty(), "Downgraded contact retained a stale grant: " + afterRecovery);

        // Re-pairing and granting are explicitly separate actions; pairing alone must disclose nothing.
        contactService.acceptInvitation(peer.id(), BASE.plusSeconds(12));
        identityService.resumeSharingAfterReview();
        assertFalse(contactService.isAuthorizedToPublish(peer.id(), SharingScope.DAILY_SUMMARY, BASE.plusSeconds(13)),
                "Re-pairing reactivated the pre-recovery grant without a fresh explicit permission change");
    }

    private static byte[] backupForABrandNewIdentity() {
        KeyPair keyPair = KeyPairs.generate();
        byte[] publicKey = KeyPairs.rawPublicKey(keyPair.getPublic());
        byte[] pkcs8 = KeyPairs.pkcs8PrivateKey(keyPair.getPrivate());
        byte[] header = IdentityBackupPayload.headerAssociatedData(IdentityBackupCodec.CURRENT_FORMAT_VERSION, BASE.toEpochMilli(), 0L, publicKey);
        EncryptedSecret sealed = PassphraseCipher.seal("backup-pass".toCharArray(), pkcs8, header);
        return IdentityBackupCodec.encode(new IdentityBackupPayload(
                IdentityBackupCodec.CURRENT_FORMAT_VERSION, BASE.toEpochMilli(), 0L, publicKey, sealed));
    }

    private static IdentityKey sampleContactKey(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return new IdentityKey(bytes);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                fail("Review latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
