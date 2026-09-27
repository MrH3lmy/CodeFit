package com.codefit.peer.identity.crypto;

import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.identity.VaultCorruptException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassphraseCipherTest {

    @Test
    void sealThenOpenRoundTripsTheOriginalPlaintext() {
        byte[] plaintext = "a sample Ed25519 PKCS#8 private key".getBytes(StandardCharsets.UTF_8);
        EncryptedSecret secret = PassphraseCipher.seal("correct horse battery staple".toCharArray(), plaintext);

        byte[] recovered = PassphraseCipher.open("correct horse battery staple".toCharArray(), secret);

        assertArrayEquals(plaintext, recovered);
    }

    @Test
    void wrongPassphraseFailsAuthenticationRatherThanReturningGarbage() {
        EncryptedSecret secret = PassphraseCipher.seal("right-passphrase".toCharArray(), "secret".getBytes(StandardCharsets.UTF_8));

        assertThrows(VaultAuthenticationException.class,
                () -> PassphraseCipher.open("wrong-passphrase".toCharArray(), secret));
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        EncryptedSecret secret = PassphraseCipher.seal("pw".toCharArray(), "secret".getBytes(StandardCharsets.UTF_8));
        byte[] tampered = secret.ciphertext();
        tampered[0] ^= 0x01;
        EncryptedSecret corrupted = new EncryptedSecret(secret.salt(), secret.iterations(), secret.nonce(), tampered);

        assertThrows(VaultAuthenticationException.class, () -> PassphraseCipher.open("pw".toCharArray(), corrupted));
    }

    @Test
    void associatedDataMustMatchOnOpen() {
        byte[] header = "header-v1".getBytes(StandardCharsets.UTF_8);
        EncryptedSecret secret = PassphraseCipher.seal("pw".toCharArray(), "secret".getBytes(StandardCharsets.UTF_8), header);

        assertThrows(VaultAuthenticationException.class,
                () -> PassphraseCipher.open("pw".toCharArray(), secret, "different-header".getBytes(StandardCharsets.UTF_8)));

        byte[] recovered = PassphraseCipher.open("pw".toCharArray(), secret, header);
        assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), recovered);
    }

    @Test
    void outOfRangeIterationCountIsRejectedBeforeAnyDecryptionAttempt() {
        EncryptedSecret secret = PassphraseCipher.seal("pw".toCharArray(), "secret".getBytes(StandardCharsets.UTF_8));
        EncryptedSecret withHugeIterations = new EncryptedSecret(secret.salt(), 50_000_000, secret.nonce(), secret.ciphertext());

        assertThrows(VaultCorruptException.class, () -> PassphraseCipher.open("pw".toCharArray(), withHugeIterations));
    }

    @Test
    void invalidSaltLengthIsRejectedAsCorrupt() {
        EncryptedSecret secret = PassphraseCipher.seal("pw".toCharArray(), "secret".getBytes(StandardCharsets.UTF_8));
        EncryptedSecret badSalt = new EncryptedSecret(new byte[]{1, 2, 3}, secret.iterations(), secret.nonce(), secret.ciphertext());

        assertThrows(VaultCorruptException.class, () -> PassphraseCipher.open("pw".toCharArray(), badSalt));
    }

    @Test
    void toStringNeverRendersKeyMaterial() {
        EncryptedSecret secret = PassphraseCipher.seal("pw".toCharArray(), "top secret bytes".getBytes(StandardCharsets.UTF_8));

        String rendered = secret.toString();

        assertTrue(rendered.contains("EncryptedSecret"));
        assertEquals(-1, rendered.indexOf("top secret"));
    }
}
