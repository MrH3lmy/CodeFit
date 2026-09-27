package com.codefit.service;

import com.codefit.peer.identity.ClockBehindPreviousEpochException;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.identity.UnsupportedBackupVersionException;
import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.identity.VaultCorruptException;
import com.codefit.peer.identity.crypto.EncryptedSecret;
import com.codefit.peer.identity.crypto.IdentityBackupCodec;
import com.codefit.peer.identity.crypto.IdentityBackupPayload;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.identity.crypto.PassphraseCipher;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers #181's encrypted backup/restore: successful round trip, wrong password, corruption, an
 * unsupported future format version, repeated restore, and that every one of those failure paths is
 * non-destructive - the existing local identity is provably untouched afterward.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class IdentityBackupServiceTest {

    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    private static Instant at(long secondsFromBase) {
        return BASE.plusSeconds(secondsFromBase);
    }

    @BeforeEach
    void resetPeerIdentityTables() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void exportThenImportRestoresTheSamePublicKeyAndPausesSharing() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.resumeSharingAfterReview();

        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(5));
        LocalIdentitySummary restored = backupService.importBackup(backup, "backup-pass".toCharArray(), "new-vault-pass".toCharArray(), at(20));

        assertEquals(original.publicKey(), restored.publicKey());
        assertEquals(original.id(), restored.id());
        assertTrue(restored.sharingPaused());
        assertTrue(identityService.isSharingPaused());
        // The restored vault only opens with the NEW passphrase supplied to importBackup.
        assertEquals(original.publicKey(), identityService.unlock("new-vault-pass".toCharArray()).publicKey());
    }

    @Test
    void wrongBackupPassphraseFailsAndLeavesTheExistingIdentityUntouched() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(5));

        assertThrows(VaultAuthenticationException.class,
                () -> backupService.importBackup(backup, "wrong-backup-pass".toCharArray(), "new-pass".toCharArray(), at(20)));

        LocalIdentitySummary stillOriginal = identityService.currentIdentity().orElseThrow();
        assertEquals(original.publicKey(), stillOriginal.publicKey());
        assertEquals(original.currentWriterEpoch(), stillOriginal.currentWriterEpoch());
        // The original vault passphrase still works: nothing was overwritten by the failed restore.
        assertEquals(original.publicKey(), identityService.unlock("vault-pass".toCharArray()).publicKey());
    }

    @Test
    void corruptBackupBytesFailAndLeaveTheExistingIdentityUntouched() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(5));
        byte[] corrupted = java.util.Arrays.copyOf(backup, backup.length - 3);

        assertThrows(VaultCorruptException.class,
                () -> backupService.importBackup(corrupted, "backup-pass".toCharArray(), "new-pass".toCharArray(), at(20)));

        assertEquals(original.publicKey(), identityService.currentIdentity().orElseThrow().publicKey());
    }

    @Test
    void unsupportedFutureFormatVersionFailsAndLeavesTheExistingIdentityUntouched() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(5));
        backup[4] = (byte) (IdentityBackupCodec.CURRENT_FORMAT_VERSION + 1);

        assertThrows(UnsupportedBackupVersionException.class,
                () -> backupService.importBackup(backup, "backup-pass".toCharArray(), "new-pass".toCharArray(), at(20)));

        assertEquals(original.publicKey(), identityService.currentIdentity().orElseThrow().publicKey());
    }

    @Test
    void restoringTheSameBackupTwiceAtAdvancingClockTimesSucceedsWithStrictlyIncreasingEpochs() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.beginWriterSession(at(5));
        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(5));

        LocalIdentitySummary firstRestore = backupService.importBackup(backup, "backup-pass".toCharArray(), "pass-2".toCharArray(), at(20));
        LocalIdentitySummary secondRestore = backupService.importBackup(backup, "backup-pass".toCharArray(), "pass-3".toCharArray(), at(40));

        assertTrue(secondRestore.currentWriterEpoch() > firstRestore.currentWriterEpoch());
        assertEquals(firstRestore.publicKey(), secondRestore.publicKey());
    }

    @Test
    void restoringWithoutTheClockAdvancingPastTheKnownEpochFailsNonDestructively() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.beginWriterSession(at(100));
        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(100));

        // The backup's own lastKnownEpoch is epochAt(100); restoring at an earlier clock time can
        // never mint a fresher epoch than that, so the restore must be refused, not silently accepted
        // with a stale/reused epoch.
        assertThrows(ClockBehindPreviousEpochException.class,
                () -> backupService.importBackup(backup, "backup-pass".toCharArray(), "pass-2".toCharArray(), at(50)));

        LocalIdentitySummary stillOriginal = identityService.currentIdentity().orElseThrow();
        assertEquals(original.publicKey(), stillOriginal.publicKey());
        assertEquals(original.publicKey(), identityService.unlock("vault-pass".toCharArray()).publicKey());
    }

    @Test
    void aBackupWhosePrivateKeyDoesNotMatchItsDeclaredPublicKeyIsRejectedNonDestructively() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        LocalIdentitySummary original = identityService.createIdentity("vault-pass".toCharArray(), at(0));

        byte[] genuineBackup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(5));
        IdentityBackupPayload genuine = IdentityBackupCodec.decode(genuineBackup);
        // Reseal an UNRELATED private key under the genuine backup's own declared public key and header
        // (correct AAD, correct passphrase) - the header/AAD binding the review's finding pointed at
        // authenticates only that the header wasn't tampered with, not that the sealed key is its pair.
        KeyPair impostor = KeyPairs.generate();
        EncryptedSecret mismatched = PassphraseCipher.seal("backup-pass".toCharArray(),
                KeyPairs.pkcs8PrivateKey(impostor.getPrivate()), genuine.headerAssociatedData());
        byte[] tamperedBackup = IdentityBackupCodec.encode(new IdentityBackupPayload(genuine.formatVersion(),
                genuine.createdAtEpochMillis(), genuine.lastKnownEpoch(), genuine.identityPublicKey(), mismatched));

        assertThrows(VaultCorruptException.class,
                () -> backupService.importBackup(tamperedBackup, "backup-pass".toCharArray(), "new-pass".toCharArray(), at(20)));

        assertEquals(original.publicKey(), identityService.currentIdentity().orElseThrow().publicKey());
        assertEquals(original.publicKey(), identityService.unlock("vault-pass".toCharArray()).publicKey());
    }

    @Test
    void importingABackupForADifferentIdentityDowngradesPairedContacts() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        ContactService contactService = new ContactService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.resumeSharingAfterReview();
        Contact contact = contactService.registerPendingContact(sampleContactKey((byte) 42), "Sam", at(1));
        contactService.acceptInvitation(contact.id(), at(2));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(3));
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(3)));

        byte[] backupForAnotherIdentity = backupForABrandNewIdentity("other-backup-pass".toCharArray(), at(4));
        backupService.importBackup(backupForAnotherIdentity, "other-backup-pass".toCharArray(), "new-vault-pass".toCharArray(), at(10));

        // Nothing here can prove continuity with whatever this contact currently trusts under the OLD
        // identity, so the pairing cannot simply carry over to the restored (different) identity.
        assertEquals(TrustState.PENDING, contactService.requireContact(contact.id()).trustState());
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(10)));
    }

    @Test
    void importingABackupForTheSameIdentityLeavesPairedContactsUntouched() {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        ContactService contactService = new ContactService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.resumeSharingAfterReview();
        Contact contact = contactService.registerPendingContact(sampleContactKey((byte) 43), "Tia", at(1));
        contactService.acceptInvitation(contact.id(), at(2));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(3));

        byte[] backup = backupService.exportBackup("vault-pass".toCharArray(), "backup-pass".toCharArray(), at(4));
        backupService.importBackup(backup, "backup-pass".toCharArray(), "new-vault-pass".toCharArray(), at(10));

        assertEquals(TrustState.PAIRED, contactService.requireContact(contact.id()).trustState());
        identityService.resumeSharingAfterReview();
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(10)));
    }

    @Test
    void importingADifferentIdentityRollsBackContactDowngradesWhenTheIdentityWriteFails() throws java.sql.SQLException {
        IdentityService identityService = new IdentityService();
        IdentityBackupService backupService = new IdentityBackupService();
        ContactService contactService = new ContactService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        identityService.resumeSharingAfterReview();
        Contact contact = contactService.registerPendingContact(sampleContactKey((byte) 88), "Xin", at(1));
        contactService.acceptInvitation(contact.id(), at(2));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(3));
        byte[] backupForAnotherIdentity = backupForABrandNewIdentity("other-backup-pass".toCharArray(), at(4));

        // peer_identity is a singleton row shared by every test in this class (IsolatedDatabaseExtension
        // isolates per class, not per test), so this trigger MUST be dropped again before it can break
        // every later exportBackup/importBackup/createIdentity call in this class.
        try (java.sql.Connection connection = com.codefit.config.DatabaseConfig.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER force_import_identity_failure BEFORE INSERT ON peer_identity
                    BEGIN SELECT RAISE(ABORT, 'forced failure for atomicity test'); END
                    """);
        }
        try {
            assertThrows(IllegalStateException.class, () -> backupService.importBackup(
                    backupForAnotherIdentity, "other-backup-pass".toCharArray(), "new-vault-pass".toCharArray(), at(10)));

            // Neither write in the same transaction may apply: the ORIGINAL identity is still active,
            // and the contact must still be PAIRED with its grant intact - not permanently stuck
            // PENDING for an identity change that never took effect.
            assertEquals(TrustState.PAIRED, contactService.requireContact(contact.id()).trustState());
            assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(10)));
        } finally {
            try (java.sql.Connection connection = com.codefit.config.DatabaseConfig.getConnection();
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER force_import_identity_failure");
            }
        }
    }

    private static IdentityKey sampleContactKey(byte fill) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, fill);
        return new IdentityKey(bytes);
    }

    /** Builds a valid, self-contained backup for a brand-new identity never stored in this database. */
    private static byte[] backupForABrandNewIdentity(char[] backupPassphrase, Instant createdAt) {
        KeyPair keyPair = KeyPairs.generate();
        byte[] publicKey = KeyPairs.rawPublicKey(keyPair.getPublic());
        byte[] pkcs8 = KeyPairs.pkcs8PrivateKey(keyPair.getPrivate());
        byte[] header = IdentityBackupPayload.headerAssociatedData(
                IdentityBackupCodec.CURRENT_FORMAT_VERSION, createdAt.toEpochMilli(), 0L, publicKey);
        EncryptedSecret sealed = PassphraseCipher.seal(backupPassphrase, pkcs8, header);
        return IdentityBackupCodec.encode(new IdentityBackupPayload(
                IdentityBackupCodec.CURRENT_FORMAT_VERSION, createdAt.toEpochMilli(), 0L, publicKey, sealed));
    }
}
