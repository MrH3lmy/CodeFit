package com.codefit.service;

import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.security.PrivateKey;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * #181 requires that key material never appears in a summary DTO, an exception message, or (by
 * extension) anything a log line could pick up. This pins that down mechanically for the DTO every
 * other caller actually receives, and for the one exception path a caller is expected to catch and
 * possibly display.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerIdentityPrivacyGuardTest {

    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    @BeforeEach
    void resetPeerIdentityTables() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void localIdentitySummaryHasNoPrivateKeyOrCiphertextField() {
        for (Field field : LocalIdentitySummary.class.getDeclaredFields()) {
            assertFalse(PrivateKey.class.isAssignableFrom(field.getType()),
                    "LocalIdentitySummary must never carry a PrivateKey field: " + field);
            String lowerName = field.getName().toLowerCase();
            assertFalse(lowerName.contains("ciphertext") || lowerName.contains("privatekey"),
                    "LocalIdentitySummary field name suggests key material: " + field);
        }
    }

    @Test
    void wrongPassphraseExceptionMessageNeverEchoesThePassphrase() {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("correct-vault-passphrase".toCharArray(), BASE);

        VaultAuthenticationException thrown = assertThrows(VaultAuthenticationException.class,
                () -> identityService.unlock("a-guessed-wrong-passphrase".toCharArray()));

        assertFalse(thrown.getMessage().contains("a-guessed-wrong-passphrase"));
        assertFalse(thrown.getMessage().contains("correct-vault-passphrase"));
    }
}
