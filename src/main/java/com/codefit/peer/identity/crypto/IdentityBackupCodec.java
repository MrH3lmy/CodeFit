package com.codefit.peer.identity.crypto;

import com.codefit.peer.identity.UnsupportedBackupVersionException;
import com.codefit.peer.identity.VaultCorruptException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Frames an {@link IdentityBackupPayload} as a self-describing byte string: a fixed magic, an explicit
 * format version, and the fields {@link IdentityBackupCodec} needs to validate before any bytes are
 * decrypted (a corrupt or future-version file must fail before {@link PassphraseCipher#open} ever
 * runs). This is CodeFit's own file format, not protocol v1's envelope framing (which is normative for
 * peer-to-peer messages, not for a single user's own local backup).
 *
 * <pre>
 * offset  size  field
 * 0       4     magic = "CFID"
 * 4       1     formatVersion (currently 1)
 * 5       8     createdAt, epoch millis
 * 13      8     lastKnownEpoch
 * 21      32    identityPublicKey
 * 53      2     salt length (u16)
 * ...     n     salt
 * ...     4     iterations (u32)
 * ...     2     nonce length (u16)
 * ...     n     nonce
 * ...     4     ciphertext length (u32)
 * ...     n     ciphertext
 * </pre>
 */
public final class IdentityBackupCodec {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final byte[] MAGIC = "CFID".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_FIELD_LENGTH = 4_096;

    private IdentityBackupCodec() {
    }

    public static byte[] encode(IdentityBackupPayload payload) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(MAGIC);
            out.writeByte(payload.formatVersion());
            out.writeLong(payload.createdAtEpochMillis());
            out.writeLong(payload.lastKnownEpoch());
            out.write(payload.identityPublicKey());
            EncryptedSecret secret = payload.encryptedPkcs8PrivateKey();
            writeLengthPrefixed(out, secret.salt());
            out.writeInt(secret.iterations());
            writeLengthPrefixed(out, secret.nonce());
            out.writeInt(secret.ciphertext().length);
            out.write(secret.ciphertext());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /**
     * @throws UnsupportedBackupVersionException the file's own declared version is not one this build
     *                                            can read; thrown before any other validation
     * @throws VaultCorruptException              the bytes are truncated, malformed, or carry trailing
     *                                            garbage; thrown before any decryption is attempted
     */
    public static IdentityBackupPayload decode(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new VaultCorruptException("Not a CodeFit identity backup file.");
            }
            int formatVersion = in.readUnsignedByte();
            if (formatVersion != CURRENT_FORMAT_VERSION) {
                throw new UnsupportedBackupVersionException(
                        "Backup format version " + formatVersion + " is not supported by this build (supports "
                                + CURRENT_FORMAT_VERSION + ").");
            }
            long createdAt = in.readLong();
            long lastKnownEpoch = in.readLong();
            byte[] publicKey = readExact(in, KeyPairs.PUBLIC_KEY_LENGTH);
            byte[] salt = readLengthPrefixed(in);
            int iterations = in.readInt();
            byte[] nonce = readLengthPrefixed(in);
            int ciphertextLength = in.readInt();
            if (ciphertextLength < 0 || ciphertextLength > MAX_FIELD_LENGTH) {
                throw new VaultCorruptException("Backup declares an invalid ciphertext length.");
            }
            byte[] ciphertext = readExact(in, ciphertextLength);
            if (in.read() != -1) {
                throw new VaultCorruptException("Backup has trailing bytes after its declared content.");
            }
            return new IdentityBackupPayload(formatVersion, createdAt, lastKnownEpoch, publicKey,
                    new EncryptedSecret(salt, iterations, nonce, ciphertext));
        } catch (EOFException e) {
            throw new VaultCorruptException("Backup file is truncated.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeLengthPrefixed(DataOutputStream out, byte[] value) throws IOException {
        out.writeShort(value.length);
        out.write(value);
    }

    private static byte[] readLengthPrefixed(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_FIELD_LENGTH) {
            throw new VaultCorruptException("Backup declares a field length that is out of bounds.");
        }
        return readExact(in, length);
    }

    private static byte[] readExact(DataInputStream in, int length) throws IOException {
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }
}
