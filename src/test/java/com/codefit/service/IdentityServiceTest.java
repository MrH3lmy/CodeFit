package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ClockBehindPreviousEpochException;
import com.codefit.peer.identity.IdentityAlreadyExistsException;
import com.codefit.peer.identity.IdentityNotFoundException;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.identity.VaultCorruptException;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.repository.PeerIdentityRepository;
import com.codefit.repository.PeerIdentityRow;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers #181's identity lifecycle: creation, restart persistence, never silently regenerating on a
 * load failure, writer-epoch session monotonicity and clock rollback, and key rotation/reset. Runs
 * against its own isolated database (#175), never {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class IdentityServiceTest {

    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    private static Instant at(long secondsFromBase) {
        return BASE.plusSeconds(secondsFromBase);
    }

    @BeforeEach
    void resetPeerIdentityTables() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void createIdentityPersistsAcrossRestart() {
        IdentityService identityService = new IdentityService();
        LocalIdentitySummary created = identityService.createIdentity("vault-pass".toCharArray(), at(0));

        // Fresh service and repository instances simulate a restarted application reading the same
        // on-disk database rather than reusing in-memory state from the object that wrote it.
        IdentityService afterRestart = new IdentityService();
        LocalIdentitySummary reloaded = afterRestart.currentIdentity().orElseThrow();

        assertEquals(created.id(), reloaded.id());
        assertEquals(created.publicKey(), reloaded.publicKey());
        assertEquals(created.currentWriterEpoch(), reloaded.currentWriterEpoch());
    }

    @Test
    void creatingASecondIdentityIsRejected() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));

        assertThrows(IdentityAlreadyExistsException.class,
                () -> identityService.createIdentity("other-pass".toCharArray(), at(1)));
    }

    @Test
    void unlockWithTheCorrectPassphraseCanSignAndReturnsTheSamePublicKey() {
        IdentityService identityService = new IdentityService();
        LocalIdentitySummary created = identityService.createIdentity("vault-pass".toCharArray(), at(0));

        UnlockedIdentity unlocked = identityService.unlock("vault-pass".toCharArray());

        assertEquals(created.publicKey(), unlocked.publicKey());
        byte[] signature = unlocked.sign("a message".getBytes());
        assertEquals(64, signature.length);
    }

    @Test
    void wrongPassphraseNeverSilentlyRegeneratesTheIdentity() {
        IdentityService identityService = new IdentityService();
        LocalIdentitySummary created = identityService.createIdentity("vault-pass".toCharArray(), at(0));

        assertThrows(VaultAuthenticationException.class, () -> identityService.unlock("wrong-pass".toCharArray()));
        assertThrows(VaultAuthenticationException.class, () -> identityService.unlock("still-wrong".toCharArray()));

        LocalIdentitySummary afterFailedAttempts = identityService.currentIdentity().orElseThrow();
        assertEquals(created.publicKey(), afterFailedAttempts.publicKey());
        assertEquals(created.id(), afterFailedAttempts.id());
    }

    @Test
    void corruptedStoredKeyMaterialNeverSilentlyRegeneratesTheIdentity() {
        IdentityService identityService = new IdentityService();
        LocalIdentitySummary created = identityService.createIdentity("vault-pass".toCharArray(), at(0));

        PeerIdentityRepository repository = new PeerIdentityRepository();
        PeerIdentityRow row = repository.find().orElseThrow();
        byte[] corruptedCiphertext = row.privateKeyCiphertext();
        corruptedCiphertext[0] ^= 0x01;
        repository.replace(new PeerIdentityRow(row.identityPublicKey(), corruptedCiphertext, row.privateKeySalt(),
                row.privateKeyIterations(), row.privateKeyNonce(), row.keyFormatVersion(), row.highestKnownEpoch(),
                row.currentWriterEpoch(), row.sharingPaused(), row.createdAt(), row.lastRestoredAt()));

        assertThrows(VaultAuthenticationException.class, () -> identityService.unlock("vault-pass".toCharArray()));

        LocalIdentitySummary stillTheSameIdentity = identityService.currentIdentity().orElseThrow();
        assertEquals(created.publicKey(), stillTheSameIdentity.publicKey());
    }

    @Test
    void structurallyInvalidStoredKeyMaterialIsReportedAsCorruptNotAuthenticationFailure() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));

        PeerIdentityRepository repository = new PeerIdentityRepository();
        PeerIdentityRow row = repository.find().orElseThrow();
        repository.replace(new PeerIdentityRow(row.identityPublicKey(), row.privateKeyCiphertext(), new byte[]{1, 2},
                row.privateKeyIterations(), row.privateKeyNonce(), row.keyFormatVersion(), row.highestKnownEpoch(),
                row.currentWriterEpoch(), row.sharingPaused(), row.createdAt(), row.lastRestoredAt()));

        assertThrows(VaultCorruptException.class, () -> identityService.unlock("vault-pass".toCharArray()));
    }

    @Test
    void unlockingWithNoIdentityYetThrowsIdentityNotFound() {
        IdentityService identityService = new IdentityService();

        assertThrows(IdentityNotFoundException.class, () -> identityService.unlock("anything".toCharArray()));
    }

    @Test
    void writerEpochsAreStrictlyIncreasingAcrossSessions() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));

        long firstSession = identityService.beginWriterSession(at(10));
        long secondSession = identityService.beginWriterSession(at(20));

        assertTrue(secondSession > firstSession);
    }

    @Test
    void beginningASecondSessionInTheSameClockSecondThrowsWithoutPersistingAnything() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        long firstSession = identityService.beginWriterSession(at(10));

        // Same instant as the session already recorded: models two concurrent writers, or a rollback,
        // trying to mint sessions at the same second - the MVP's documented single-writer limitation.
        assertThrows(ClockBehindPreviousEpochException.class, () -> identityService.beginWriterSession(at(10)));

        assertEquals(firstSession, identityService.currentIdentity().orElseThrow().currentWriterEpoch());
    }

    @Test
    void clockRollingBackBehindThePreviousEpochIsRefusedNotSilentlyAccepted() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.beginWriterSession(at(100));

        assertThrows(ClockBehindPreviousEpochException.class, () -> identityService.beginWriterSession(at(50)));
    }

    @Test
    void rotateKeyWithContinuityChangesTheKeyButKeepsPairedContactsAndPausesSharing() {
        IdentityService identityService = new IdentityService();
        ContactService contactService = new ContactService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.resumeSharingAfterReview();
        Contact contact = contactService.registerPendingContact(sampleContactKey((byte) 9), "Ada", at(1));
        contactService.acceptInvitation(contact.id(), at(2));
        contactService.updatePermissions(contact.id(), new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(3));

        LocalIdentitySummary rotated = identityService.rotateKeyWithContinuity(
                "vault-pass".toCharArray(), "new-vault-pass".toCharArray(), at(10));

        assertNotEquals(original.publicKey(), rotated.publicKey());
        assertNotEquals(original.id(), rotated.id());
        assertTrue(identityService.isSharingPaused());
        Contact afterRotation = contactService.requireContact(contact.id());
        assertEquals(TrustState.PAIRED, afterRotation.trustState());
        // The grant itself survived the rotation, but the pause denies publication until reviewed.
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(10)));
        identityService.resumeSharingAfterReview();
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(10)));
        // The old passphrase no longer opens anything: the vault now holds the new key.
        assertThrows(VaultAuthenticationException.class, () -> identityService.unlock("vault-pass".toCharArray()));
        UnlockedIdentity unlockedWithNewPassphrase = identityService.unlock("new-vault-pass".toCharArray());
        assertEquals(rotated.publicKey(), unlockedWithNewPassphrase.publicKey());

        com.codefit.peer.identity.KeyContinuityRecord continuity =
                new com.codefit.repository.IdentityKeyRotationRepository().findLatest().orElseThrow();
        assertEquals(original.publicKey(), continuity.oldKey());
        assertEquals(rotated.publicKey(), continuity.newKey());
        assertTrue(continuity.verify());
    }

    @Test
    void resetIdentityWithoutContinuityDowngradesPairedContactsAndRevokesTheirGrants() {
        IdentityService identityService = new IdentityService();
        ContactService contactService = new ContactService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.resumeSharingAfterReview();
        Contact contact = contactService.registerPendingContact(sampleContactKey((byte) 11), "Ben", at(1));
        contactService.acceptInvitation(contact.id(), at(2));
        contactService.updatePermissions(contact.id(), new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), at(3));
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.DAILY_SUMMARY, at(3)));

        LocalIdentitySummary reset = identityService.resetIdentityWithoutContinuity("brand-new-pass".toCharArray(), at(10));

        assertNotEquals(original.publicKey(), reset.publicKey());
        Contact afterReset = contactService.requireContact(contact.id());
        assertEquals(TrustState.PENDING, afterReset.trustState());
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.DAILY_SUMMARY, at(10)));
        assertTrue(identityService.isSharingPaused());
        // No signed continuity proof exists for a reset without the old key - that is the whole reason
        // contacts had to be downgraded above instead of carried over.
        assertTrue(new com.codefit.repository.IdentityKeyRotationRepository().findLatest().isEmpty());
    }

    @Test
    void unlockRejectsAStoredPrivateKeyThatDoesNotMatchTheStoredPublicKey() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        PeerIdentityRepository repository = new PeerIdentityRepository();
        PeerIdentityRow row = repository.find().orElseThrow();

        // Craft a row whose ciphertext decrypts to an UNRELATED private key, sealed under this row's
        // own declared public key as associated data. AEAD authentication alone passes (the AAD the
        // reviewer's finding pointed at matches exactly what open() will supply), but the decrypted
        // key and the declared public key are not actually a pair - the exact gap #192's review found.
        var impostorKeyPair = com.codefit.peer.identity.crypto.KeyPairs.generate();
        var mismatchedSecret = com.codefit.peer.identity.crypto.PassphraseCipher.seal("vault-pass".toCharArray(),
                com.codefit.peer.identity.crypto.KeyPairs.pkcs8PrivateKey(impostorKeyPair.getPrivate()), row.identityPublicKey());
        repository.replace(new PeerIdentityRow(row.identityPublicKey(), mismatchedSecret.ciphertext(),
                mismatchedSecret.salt(), mismatchedSecret.iterations(), mismatchedSecret.nonce(), row.keyFormatVersion(),
                row.highestKnownEpoch(), row.currentWriterEpoch(), row.sharingPaused(), row.createdAt(), row.lastRestoredAt()));

        assertThrows(VaultCorruptException.class, () -> identityService.unlock("vault-pass".toCharArray()));
    }

    @Test
    void rotateKeyWithContinuityRollsBackTheNewIdentityRowWhenTheContinuityWriteFails() throws java.sql.SQLException {
        IdentityService identityService = new IdentityService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        Instant rotationInstant = at(9_999);

        try (java.sql.Connection connection = com.codefit.config.DatabaseConfig.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER force_rotation_failure BEFORE INSERT ON identity_key_rotations
                    WHEN NEW.rotated_at = '%s'
                    BEGIN SELECT RAISE(ABORT, 'forced failure for atomicity test'); END
                    """.formatted(rotationInstant));
        }

        assertThrows(IllegalStateException.class, () -> identityService.rotateKeyWithContinuity(
                "vault-pass".toCharArray(), "new-pass".toCharArray(), rotationInstant));

        // A crash between the two writes must never leave the new key active without its continuity
        // proof: the OLD identity must still be the one in effect, still unlockable with its own passphrase.
        assertEquals(original.publicKey(), identityService.currentIdentity().orElseThrow().publicKey());
        assertEquals(original.publicKey(), identityService.unlock("vault-pass".toCharArray()).publicKey());
    }

    @Test
    void resettingWithNoIdentityYetThrowsIdentityNotFound() {
        IdentityService identityService = new IdentityService();

        assertThrows(IdentityNotFoundException.class,
                () -> identityService.resetIdentityWithoutContinuity("pass".toCharArray(), at(0)));
    }

    private static IdentityKey sampleContactKey(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return new IdentityKey(bytes);
    }
}
