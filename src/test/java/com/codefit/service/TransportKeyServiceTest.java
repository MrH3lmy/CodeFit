package com.codefit.service;

import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.transport.TransportKeyMaterial;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers #182's local transport key lifecycle: creation, restart persistence, reuse while valid,
 * automatic renewal before expiry, explicit rotation, and wrong-passphrase handling. Runs against its
 * own isolated database, never {@code codefit.db}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class TransportKeyServiceTest {
    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    @BeforeEach
    void resetTables() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void createsAndReusesTheSameKeyWhileItRemainsValid() {
        TransportKeyService service = new TransportKeyService();
        TransportKeyMaterial first = service.ensureCurrent("vault-pass".toCharArray(), BASE);
        TransportKeyMaterial second = service.ensureCurrent("vault-pass".toCharArray(), BASE.plusSeconds(60));

        assertArrayEquals(first.keyPair().getPublic().getEncoded(), second.keyPair().getPublic().getEncoded());
        assertEquals(first.validFrom(), second.validFrom());
        assertEquals(first.validUntil(), second.validUntil());
    }

    @Test
    void persistsAcrossRestart() {
        TransportKeyMaterial created = new TransportKeyService().ensureCurrent("vault-pass".toCharArray(), BASE);
        TransportKeyMaterial reloaded = new TransportKeyService().ensureCurrent("vault-pass".toCharArray(), BASE.plusSeconds(5));
        assertArrayEquals(created.keyPair().getPublic().getEncoded(), reloaded.keyPair().getPublic().getEncoded());
        assertArrayEquals(created.keyPair().getPrivate().getEncoded(), reloaded.keyPair().getPrivate().getEncoded());
    }

    @Test
    void automaticallyRenewsWithinTheRenewalWindowBeforeExpiry() {
        TransportKeyService service = new TransportKeyService();
        TransportKeyMaterial first = service.ensureCurrent("vault-pass".toCharArray(), BASE);
        // 179 days later: within 7 days of the 180-day validity period, so a fresh key is minted.
        Instant nearExpiry = BASE.plus(Duration.ofDays(179));
        TransportKeyMaterial renewed = service.ensureCurrent("vault-pass".toCharArray(), nearExpiry);

        assertFalse(java.util.Arrays.equals(first.keyPair().getPublic().getEncoded(), renewed.keyPair().getPublic().getEncoded()));
        assertTrue(renewed.validUntil().isAfter(first.validUntil()));
    }

    @Test
    void rotateAlwaysMintsAFreshKeyRegardlessOfRemainingValidity() {
        TransportKeyService service = new TransportKeyService();
        TransportKeyMaterial first = service.ensureCurrent("vault-pass".toCharArray(), BASE);
        TransportKeyMaterial rotated = service.rotate("vault-pass".toCharArray(), BASE.plusSeconds(10));
        assertFalse(java.util.Arrays.equals(first.keyPair().getPublic().getEncoded(), rotated.keyPair().getPublic().getEncoded()));
    }

    @Test
    void wrongPassphraseFailsAuthenticationRatherThanSilentlyRegeneratingOrReturningGarbage() {
        TransportKeyService service = new TransportKeyService();
        service.ensureCurrent("correct-pass".toCharArray(), BASE);
        assertThrows(VaultAuthenticationException.class, () -> service.ensureCurrent("wrong-pass".toCharArray(), BASE.plusSeconds(5)));
    }
}
