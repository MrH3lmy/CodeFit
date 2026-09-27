package com.codefit.service;

import com.codefit.peer.identity.ClockBehindPreviousEpochException;
import com.codefit.peer.identity.IdentityAlreadyExistsException;
import com.codefit.peer.identity.IdentityNotFoundException;
import com.codefit.peer.identity.KeyContinuityRecord;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.VaultCorruptException;
import com.codefit.peer.identity.crypto.EncryptedSecret;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.identity.crypto.PassphraseCipher;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.WriterEpoch;
import com.codefit.repository.IdentityKeyRotationRepository;
import com.codefit.repository.PeerIdentityRepository;
import com.codefit.repository.PeerIdentityRow;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Owns the single local identity row: creation, unlocking for signing, writer-epoch session
 * bookkeeping, and key rotation/reset (#181). Every method that could accidentally expose the private
 * key returns {@link LocalIdentitySummary} instead; only {@link #unlock} ever hands back key material,
 * and it does so as an {@link UnlockedIdentity} the caller uses only to sign, never to read the raw
 * bytes back out. Restore (import from a backup) lives in {@link IdentityBackupService}, which reuses
 * {@link #nextEpoch} so both share the exact same clock-rollback handling.
 */
public class IdentityService {
    private final PeerIdentityRepository identityRepository;
    private final IdentityKeyRotationRepository rotationRepository;
    private final ContactService contactService;

    public IdentityService() {
        this(new PeerIdentityRepository(), new IdentityKeyRotationRepository(), new ContactService());
    }

    IdentityService(PeerIdentityRepository identityRepository, IdentityKeyRotationRepository rotationRepository,
                     ContactService contactService) {
        this.identityRepository = identityRepository;
        this.rotationRepository = rotationRepository;
        this.contactService = contactService;
    }

    public Optional<LocalIdentitySummary> currentIdentity() {
        return identityRepository.find().map(IdentityService::summaryOf);
    }

    /** @throws IdentityAlreadyExistsException a local identity already exists; use rotate/reset instead */
    public LocalIdentitySummary createIdentity(char[] vaultPassphrase, Instant now) {
        if (identityRepository.find().isPresent()) {
            throw new IdentityAlreadyExistsException(
                    "A local identity already exists; use rotateKeyWithContinuity or resetIdentityWithoutContinuity to replace it.");
        }
        KeyPair keyPair = KeyPairs.generate();
        long epoch = nextEpoch(0, now);
        PeerIdentityRow row = sealRow(keyPair, vaultPassphrase, epoch, epoch, false, now, null);
        identityRepository.replace(row);
        return summaryOf(row);
    }

    /**
     * @throws IdentityNotFoundException     no local identity exists
     * @throws com.codefit.peer.identity.VaultAuthenticationException wrong passphrase, or the stored
     *                                        ciphertext/public key pairing was tampered with
     * @throws VaultCorruptException stored key material is structurally invalid, or (once decrypted)
     *                                        does not actually pair with the stored public key
     */
    public UnlockedIdentity unlock(char[] vaultPassphrase) {
        PeerIdentityRow row = requireRow();
        byte[] pkcs8 = PassphraseCipher.open(vaultPassphrase, encryptedSecretOf(row), row.identityPublicKey());
        try {
            PrivateKey privateKey = KeyPairs.privateKeyFromPkcs8(pkcs8);
            IdentityKey publicKey = new IdentityKey(row.identityPublicKey());
            requireMatchingKeyPair(privateKey, publicKey);
            return new UnlockedIdentity(publicKey, privateKey);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    /**
     * Starts a fresh writer session for the existing identity (its {@code IdentityId} does not
     * change), per protocol §10.1. Safe to call on every launch; required after any local recovery
     * that may have lost {@code (epoch, revision)} state.
     *
     * @throws ClockBehindPreviousEpochException the clock has not advanced past the previous session's
     *                                            epoch; nothing is persisted
     */
    public long beginWriterSession(Instant now) {
        PeerIdentityRow row = requireRow();
        long epoch = nextEpoch(row.highestKnownEpoch(), now);
        identityRepository.updateEpochState(epoch, epoch);
        return epoch;
    }

    /** The explicit "I have reviewed who I'm sharing with" action required after a restore, rotation, or reset. */
    public void resumeSharingAfterReview() {
        requireRow();
        identityRepository.setSharingPaused(false);
    }

    public boolean isSharingPaused() {
        return requireRow().sharingPaused();
    }

    /**
     * Replaces the identity key while the old one is still available to vouch for the new one
     * (ADR-0001 §1). Existing contacts and their grants are left as-is (continuity is exactly the
     * point), but sharing is paused pending an explicit review, the same as after a restore.
     *
     * @throws com.codefit.peer.identity.VaultAuthenticationException {@code currentVaultPassphrase} is wrong
     */
    public LocalIdentitySummary rotateKeyWithContinuity(char[] currentVaultPassphrase, char[] newVaultPassphrase, Instant now) {
        PeerIdentityRow row = requireRow();
        UnlockedIdentity current = unlock(currentVaultPassphrase);
        KeyPair newKeyPair = KeyPairs.generate();
        IdentityKey newPublicKey = new IdentityKey(KeyPairs.rawPublicKey(newKeyPair.getPublic()));
        byte[] signature = current.sign(KeyContinuityRecord.signingBytes(current.publicKey(), newPublicKey, now));
        // A rotated key is a new IdentityId; peers key their replay state by author, so this new key's
        // own epoch history starts fresh regardless of the retiring key's highestKnownEpoch.
        long epoch = nextEpoch(0, now);
        PeerIdentityRow newRow = sealRow(newKeyPair, newVaultPassphrase, epoch, epoch, true, now, row.lastRestoredAt());
        KeyContinuityRecord continuity = new KeyContinuityRecord(current.publicKey(), newPublicKey, now, signature);
        // One transaction: a crash between these two writes must never leave the new key active
        // without the continuity proof it exists to provide.
        Transactions.run(connection -> {
            identityRepository.replace(connection, newRow);
            rotationRepository.save(connection, continuity);
        });
        return summaryOf(newRow);
    }

    /**
     * Replaces the identity key when the old one is not available (forgotten passphrase, corrupted
     * vault, no usable backup). With no continuity proof, every paired contact is downgraded so the
     * learner (or their contact) must explicitly re-pair before any sharing resumes under the new key
     * (ADR-0001 §1: "otherwise require peers to explicitly re-pair").
     *
     * @throws IdentityNotFoundException no local identity exists to reset; use {@link #createIdentity} instead
     */
    public LocalIdentitySummary resetIdentityWithoutContinuity(char[] newVaultPassphrase, Instant now) {
        requireRow();
        contactService.downgradeAllPairedContactsForIdentityReset(now);
        KeyPair newKeyPair = KeyPairs.generate();
        long epoch = nextEpoch(0, now);
        PeerIdentityRow newRow = sealRow(newKeyPair, newVaultPassphrase, epoch, epoch, true, now, null);
        identityRepository.replace(newRow);
        return summaryOf(newRow);
    }

    PeerIdentityRow requireRow() {
        return identityRepository.find().orElseThrow(() -> new IdentityNotFoundException("No local identity exists yet."));
    }

    static long nextEpoch(long previousEpoch, Instant now) {
        try {
            return WriterEpoch.next(previousEpoch, now);
        } catch (IllegalStateException e) {
            throw new ClockBehindPreviousEpochException(e.getMessage());
        }
    }

    static EncryptedSecret encryptedSecretOf(PeerIdentityRow row) {
        return new EncryptedSecret(row.privateKeySalt(), row.privateKeyIterations(), row.privateKeyNonce(), row.privateKeyCiphertext());
    }

    /**
     * A decrypted private key and a declared public key travel separately (the public key in the
     * clear, the private key inside AEAD ciphertext keyed by a passphrase), so AEAD authentication
     * alone never proves the two are actually a pair — only that the ciphertext matches whatever
     * public key was bound as associated data when it was sealed. A crafted, correctly-encrypted vault
     * row or backup could still pair private key B with declared public key A. Used by both
     * {@link #unlock} and {@link IdentityBackupService#importBackup}, always before returning or
     * persisting anything derived from the pairing.
     *
     * @throws VaultCorruptException the private key does not verify against the declared public key
     */
    static void requireMatchingKeyPair(PrivateKey privateKey, IdentityKey declaredPublicKey) {
        if (!KeyPairs.matches(privateKey, KeyPairs.publicKeyFromRaw(declaredPublicKey.bytes()))) {
            throw new VaultCorruptException("Decrypted private key does not match the declared public key.");
        }
    }

    private static PeerIdentityRow sealRow(KeyPair keyPair, char[] passphrase, long highestKnownEpoch,
                                            long currentWriterEpoch, boolean sharingPaused, Instant createdAt,
                                            Instant lastRestoredAt) {
        byte[] publicKeyRaw = KeyPairs.rawPublicKey(keyPair.getPublic());
        byte[] pkcs8 = KeyPairs.pkcs8PrivateKey(keyPair.getPrivate());
        try {
            EncryptedSecret secret = PassphraseCipher.seal(passphrase, pkcs8, publicKeyRaw);
            return new PeerIdentityRow(publicKeyRaw, secret.ciphertext(), secret.salt(), secret.iterations(),
                    secret.nonce(), 1, highestKnownEpoch, currentWriterEpoch, sharingPaused, createdAt, lastRestoredAt);
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }

    static LocalIdentitySummary summaryOf(PeerIdentityRow row) {
        IdentityKey publicKey = new IdentityKey(row.identityPublicKey());
        return new LocalIdentitySummary(publicKey.id(), publicKey, row.createdAt(), row.highestKnownEpoch(),
                row.currentWriterEpoch(), row.sharingPaused(), row.lastRestoredAt());
    }
}
