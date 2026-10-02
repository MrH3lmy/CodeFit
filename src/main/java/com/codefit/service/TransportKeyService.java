package com.codefit.service;

import com.codefit.peer.identity.crypto.EncryptedSecret;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.identity.crypto.PassphraseCipher;
import com.codefit.peer.transport.TransportKeyMaterial;
import com.codefit.repository.TransportIdentityRepository;
import com.codefit.repository.TransportIdentityRow;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Owns the single local transport key row: creation, renewal before expiry, and unlocking for TLS use
 * (#182). This is a separate key from the identity key (ADR-0001 §1): the identity key only ever signs
 * an {@code IDENTITY_BINDING} vouching for this key, and never itself touches a socket. The private key
 * is sealed at rest with the exact same scheme #181 already uses for the identity vault
 * ({@link PassphraseCipher}: PBKDF2WithHmacSHA256 + AES/GCM/NoPadding), under the same vault passphrase,
 * so enabling networking and unlocking the identity for signing are a single user action.
 */
public class TransportKeyService {
    /** Renewed automatically once fewer than this remains before the current binding expires. */
    private static final Duration RENEWAL_WINDOW = Duration.ofDays(7);
    /** How long a freshly minted transport key's binding is valid for; comfortably under the protocol's 400-day cap. */
    private static final Duration VALIDITY_PERIOD = Duration.ofDays(180);

    private final TransportIdentityRepository repository;

    public TransportKeyService() {
        this(new TransportIdentityRepository());
    }

    TransportKeyService(TransportIdentityRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the current transport key material, minting a fresh key pair (and a fresh
     * {@code [now, now + 180 days)} validity window) whenever there is none yet or the existing one
     * expires within {@link #RENEWAL_WINDOW}. This only changes the <em>persisted</em> key. Whoever holds a
     * live listener must make it present the returned key too ({@code NetworkingService} does, under one
     * lock, before it hands the key to anything else), and already-paired peers learn the new key through
     * the rollover handshake (docs/p2p/transport-v1.md §9), never by silently accepting an unproven key.
     */
    public TransportKeyMaterial ensureCurrent(char[] vaultPassphrase, Instant now) {
        Optional<TransportIdentityRow> existing = repository.find();
        if (existing.isPresent() && existing.get().bindingValidUntil().isAfter(now.plus(RENEWAL_WINDOW))) {
            return unseal(vaultPassphrase, existing.get());
        }
        return mintAndPersist(vaultPassphrase, now);
    }

    /** The persisted current transport public key (no passphrase needed: it is public), if one exists. */
    public Optional<com.codefit.peer.protocol.IdentityKey> currentPublicKey() {
        return repository.find().map(row -> new com.codefit.peer.protocol.IdentityKey(row.transportPublicKey()));
    }

    /** Forces a brand-new transport key regardless of the current one's remaining validity. */
    public TransportKeyMaterial rotate(char[] vaultPassphrase, Instant now) {
        return mintAndPersist(vaultPassphrase, now);
    }

    private TransportKeyMaterial mintAndPersist(char[] vaultPassphrase, Instant now) {
        KeyPair keyPair = KeyPairs.generate();
        // Whole seconds, and strictly later than the key being replaced: the binding revision peers order
        // rollovers by is validFrom in epoch seconds, so it must never repeat or go backwards even when
        // two keys are minted within the same second or the clock steps back.
        Instant validFrom = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Optional<TransportIdentityRow> previous = repository.find();
        if (previous.isPresent() && !validFrom.isAfter(previous.get().bindingValidFrom())) {
            validFrom = previous.get().bindingValidFrom().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(1);
        }
        Instant validUntil = validFrom.plus(VALIDITY_PERIOD);
        byte[] publicKeyRaw = KeyPairs.rawPublicKey(keyPair.getPublic());
        byte[] pkcs8 = KeyPairs.pkcs8PrivateKey(keyPair.getPrivate());
        try {
            EncryptedSecret secret = PassphraseCipher.seal(vaultPassphrase, pkcs8, publicKeyRaw);
            repository.replace(new TransportIdentityRow(publicKeyRaw, secret.ciphertext(), secret.salt(),
                    secret.iterations(), secret.nonce(), validFrom, validUntil, now));
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
        return new TransportKeyMaterial(keyPair, validFrom, validUntil);
    }

    private TransportKeyMaterial unseal(char[] vaultPassphrase, TransportIdentityRow row) {
        EncryptedSecret secret = new EncryptedSecret(row.privateKeySalt(), row.privateKeyIterations(), row.privateKeyNonce(), row.privateKeyCiphertext());
        byte[] pkcs8 = PassphraseCipher.open(vaultPassphrase, secret, row.transportPublicKey());
        try {
            PrivateKey privateKey = KeyPairs.privateKeyFromPkcs8(pkcs8);
            java.security.PublicKey publicKey = KeyPairs.publicKeyFromRaw(row.transportPublicKey());
            if (!KeyPairs.matches(privateKey, publicKey)) {
                throw new IllegalStateException("Decrypted transport private key does not match the stored public key.");
            }
            return new TransportKeyMaterial(new KeyPair(publicKey, privateKey), row.bindingValidFrom(), row.bindingValidUntil());
        } finally {
            Arrays.fill(pkcs8, (byte) 0);
        }
    }
}
