package com.codefit.service;

import com.codefit.peer.identity.ClockBehindPreviousEpochException;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.UnsupportedBackupVersionException;
import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.identity.VaultCorruptException;
import com.codefit.peer.identity.crypto.IdentityBackupCodec;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
