package com.codefit.service;

import com.codefit.peer.identity.IdentityNotFoundException;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.crypto.EncryptedSecret;
import com.codefit.peer.identity.crypto.IdentityBackupCodec;
import com.codefit.peer.identity.crypto.IdentityBackupPayload;
import com.codefit.peer.identity.crypto.PassphraseCipher;
import com.codefit.repository.PeerIdentityRepository;
import com.codefit.repository.PeerIdentityRow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Encrypted, user-held identity backup/export and restore (#181). A backup contains exactly the
 * identity private key (sealed with its own passphrase, which may differ from the local vault's) and
 * the bookkeeping a restore needs to resume writer-epoch sessions safely; it is never the learning
 * database, and never anything a summary DTO would expose. Every failure mode below — wrong backup
 * passphrase, corrupted file, unsupported format version, or a clock behind a previously used epoch —
 * is validated <em>before</em> {@link #importBackup} writes anything, so a failed restore always
 * leaves the existing local identity exactly as it was.
 */
public class IdentityBackupService {
    private final PeerIdentityRepository identityRepository;

    public IdentityBackupService() {
        this(new PeerIdentityRepository());
    }

    IdentityBackupService(PeerIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    /**
     * @throws IdentityNotFoundException no local identity exists to back up
     * @throws com.codefit.peer.identity.VaultAuthenticationException {@code vaultPassphrase} is wrong
     */
    public byte[] exportBackup(char[] vaultPassphrase, char[] backupPassphrase, Instant now) {
        PeerIdentityRow row = identityRepository.find()
                .orElseThrow(() -> new IdentityNotFoundException("No local identity exists to back up."));
        byte[] pkcs8 = PassphraseCipher.open(vaultPassphrase, IdentityService.encryptedSecretOf(row), row.identityPublicKey());
        try {
            long createdAtMillis = now.toEpochMilli();
            byte[] header = IdentityBackupPayload.headerAssociatedData(
                    IdentityBackupCodec.CURRENT_FORMAT_VERSION, createdAtMillis, row.highestKnownEpoch(), row.identityPublicKey());
            EncryptedSecret sealed = PassphraseCipher.seal(backupPassphrase, pkcs8, header);
            IdentityBackupPayload payload = new IdentityBackupPayload(IdentityBackupCodec.CURRENT_FORMAT_VERSION,
                    createdAtMillis, row.highestKnownEpoch(), row.identityPublicKey(), sealed);
            return IdentityBackupCodec.encode(payload);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    public void exportBackupToFile(Path path, char[] vaultPassphrase, char[] backupPassphrase, Instant now) throws IOException {
        Files.write(path, exportBackup(vaultPassphrase, backupPassphrase, now));
    }

    /**
     * Restores signing capability from a backup under a (possibly new) local vault passphrase, and
     * immediately starts a fresh writer session using the higher of this device's own known epoch and
     * the backup's, so restoring the same backup twice — or restoring onto a device that kept
     * publishing after the backup was made — can never reuse an epoch (protocol §10.1). Sharing is
     * left paused: the restored consent state may predate a revocation.
     *
     * @throws com.codefit.peer.identity.VaultCorruptException the file is not a recognizable backup
     * @throws com.codefit.peer.identity.UnsupportedBackupVersionException the file's format version
     *                                                                     is newer than this build supports
     * @throws com.codefit.peer.identity.VaultAuthenticationException {@code backupPassphrase} is wrong,
     *                                                                 or the file was tampered with
     * @throws com.codefit.peer.identity.ClockBehindPreviousEpochException the local clock has not
     *                                                                     advanced past an epoch already used
     */
    public LocalIdentitySummary importBackup(byte[] backupBytes, char[] backupPassphrase, char[] newVaultPassphrase, Instant now) {
        IdentityBackupPayload payload = IdentityBackupCodec.decode(backupBytes);
        byte[] pkcs8 = PassphraseCipher.open(backupPassphrase, payload.encryptedPkcs8PrivateKey(), payload.headerAssociatedData());
        try {
            Optional<PeerIdentityRow> existing = identityRepository.find();
            long previousEpoch = Math.max(existing.map(PeerIdentityRow::highestKnownEpoch).orElse(0L), payload.lastKnownEpoch());
            long newEpoch = IdentityService.nextEpoch(previousEpoch, now);
            Instant createdAt = existing.map(PeerIdentityRow::createdAt).orElse(Instant.ofEpochMilli(payload.createdAtEpochMillis()));
            EncryptedSecret resealed = PassphraseCipher.seal(newVaultPassphrase, pkcs8, payload.identityPublicKey());
            PeerIdentityRow restoredRow = new PeerIdentityRow(payload.identityPublicKey(), resealed.ciphertext(),
                    resealed.salt(), resealed.iterations(), resealed.nonce(), 1, newEpoch, newEpoch, true, createdAt, now);
            identityRepository.replace(restoredRow);
            return IdentityService.summaryOf(restoredRow);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    public LocalIdentitySummary importBackupFromFile(Path path, char[] backupPassphrase, char[] newVaultPassphrase, Instant now) throws IOException {
        return importBackup(Files.readAllBytes(path), backupPassphrase, newVaultPassphrase, now);
    }
}
