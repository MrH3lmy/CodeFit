package com.codefit.peer.identity.crypto;

import com.codefit.peer.identity.UnsupportedBackupVersionException;
import com.codefit.peer.identity.VaultCorruptException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentityBackupCodecTest {

    private static IdentityBackupPayload samplePayload() {
        byte[] publicKey = new byte[KeyPairs.PUBLIC_KEY_LENGTH];
        Arrays.fill(publicKey, (byte) 7);
        EncryptedSecret secret = PassphraseCipher.seal("backup-passphrase".toCharArray(),
                "pkcs8-private-key-bytes".getBytes(StandardCharsets.UTF_8));
        return new IdentityBackupPayload(IdentityBackupCodec.CURRENT_FORMAT_VERSION, 1_735_000_000_000L, 42L, publicKey, secret);
    }

    @Test
    void encodeThenDecodeRoundTrips() {
        IdentityBackupPayload payload = samplePayload();

        IdentityBackupPayload decoded = IdentityBackupCodec.decode(IdentityBackupCodec.encode(payload));

        assertEquals(payload, decoded);
    }

    @Test
    void truncatedFileIsReportedAsCorruptNotAsAnAuthenticationFailure() {
        byte[] encoded = IdentityBackupCodec.encode(samplePayload());
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 10);

        assertThrows(VaultCorruptException.class, () -> IdentityBackupCodec.decode(truncated));
    }

    @Test
    void trailingGarbageAfterTheDeclaredContentIsRejected() {
        byte[] encoded = IdentityBackupCodec.encode(samplePayload());
        byte[] withTrailingBytes = Arrays.copyOf(encoded, encoded.length + 5);

        assertThrows(VaultCorruptException.class, () -> IdentityBackupCodec.decode(withTrailingBytes));
    }

    @Test
    void wrongMagicIsRejectedAsNotABackupFile() {
        byte[] encoded = IdentityBackupCodec.encode(samplePayload());
        encoded[0] = (byte) 'X';

        assertThrows(VaultCorruptException.class, () -> IdentityBackupCodec.decode(encoded));
    }

    @Test
    void futureFormatVersionIsRejectedBeforeAnyDecryption() {
        byte[] encoded = IdentityBackupCodec.encode(samplePayload());
        encoded[4] = (byte) (IdentityBackupCodec.CURRENT_FORMAT_VERSION + 1);

        assertThrows(UnsupportedBackupVersionException.class, () -> IdentityBackupCodec.decode(encoded));
    }

    @Test
    void tamperingWithThePlaintextPublicKeyHeaderIsDetectedWhenTheSecretIsLaterOpenedWithHeaderAad() {
        byte[] publicKey = new byte[KeyPairs.PUBLIC_KEY_LENGTH];
        Arrays.fill(publicKey, (byte) 7);
        byte[] correctHeader = IdentityBackupPayload.headerAssociatedData(
                IdentityBackupCodec.CURRENT_FORMAT_VERSION, 1_735_000_000_000L, 42L, publicKey);
        EncryptedSecret sealedWithHeaderBound = PassphraseCipher.seal("backup-passphrase".toCharArray(),
                "pkcs8-private-key-bytes".getBytes(StandardCharsets.UTF_8), correctHeader);

        byte[] tamperedPublicKey = publicKey.clone();
        tamperedPublicKey[0] ^= 0x01;
        byte[] tamperedHeader = IdentityBackupPayload.headerAssociatedData(
                IdentityBackupCodec.CURRENT_FORMAT_VERSION, 1_735_000_000_000L, 42L, tamperedPublicKey);

        assertThrows(com.codefit.peer.identity.VaultAuthenticationException.class,
                () -> PassphraseCipher.open("backup-passphrase".toCharArray(), sealedWithHeaderBound, tamperedHeader));
        assertArrayEquals("pkcs8-private-key-bytes".getBytes(StandardCharsets.UTF_8),
                PassphraseCipher.open("backup-passphrase".toCharArray(), sealedWithHeaderBound, correctHeader));
    }
}
