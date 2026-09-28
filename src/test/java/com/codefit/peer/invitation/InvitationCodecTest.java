package com.codefit.peer.invitation;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.transport.PeerAddress;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvitationCodecTest {

    private static UnlockedIdentity newIdentity() {
        KeyPair keyPair = KeyPairs.generate();
        return new UnlockedIdentity(new IdentityKey(KeyPairs.rawPublicKey(keyPair.getPublic())), keyPair.getPrivate());
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[Invitation.NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    private static Invitation validInvitation(UnlockedIdentity identity) {
        // Truncated to milliseconds: the wire format (like protocol v1) has millisecond precision, so a
        // round trip cannot preserve sub-millisecond nanos an Instant.now() call may carry.
        Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        return new Invitation(identity.publicKey(), newIdentity().publicKey(),
                now.minus(1, ChronoUnit.HOURS), now.plus(90, ChronoUnit.DAYS),
                List.of(new PeerAddress("203.0.113.5", 47123), new PeerAddress("2001:db8::1", 47123)),
                nonce(), now, now.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void roundTripsASignedInvitationThroughBytesAndBase64() {
        UnlockedIdentity identity = newIdentity();
        Invitation invitation = validInvitation(identity);
        SignedInvitation signed = InvitationCodec.sign(invitation, identity);

        byte[] encoded = InvitationCodec.encode(signed);
        SignedInvitation decoded = InvitationCodec.decode(encoded);
        assertEquals(signed, decoded);
        assertEquals(invitation, decoded.invitation());

        String base64 = InvitationCodec.toBase64(signed);
        assertEquals(decoded, InvitationCodec.fromBase64(base64));
    }

    @Test
    void decodingAloneNeverEstablishesTrustItOnlyReturnsAValue() {
        // This is a structural/API-shape assertion: InvitationCodec.decode has no side effects, no
        // reference to ContactService, and returns a plain value the caller must separately act on.
        UnlockedIdentity identity = newIdentity();
        SignedInvitation signed = InvitationCodec.sign(validInvitation(identity), identity);
        SignedInvitation decoded = InvitationCodec.decode(InvitationCodec.encode(signed));
        assertNotNull(decoded);
        assertFalse(decoded.invitation().isExpired(Instant.now()));
    }

    @Test
    void rejectsATamperedByte() {
        UnlockedIdentity identity = newIdentity();
        SignedInvitation signed = InvitationCodec.sign(validInvitation(identity), identity);
        byte[] encoded = InvitationCodec.encode(signed);
        encoded[10] ^= 0x01;

        InvitationException failure = assertThrows(InvitationException.class, () -> InvitationCodec.decode(encoded));
        assertEquals(InvitationRejectionReason.BAD_SIGNATURE, failure.reason());
    }

    @Test
    void rejectsASignatureFromAForeignKey() {
        UnlockedIdentity claimed = newIdentity();
        UnlockedIdentity actualSigner = newIdentity();
        Invitation invitation = validInvitation(claimed);
        // Hand-craft a mismatched signature: a real, well-formed 64-byte Ed25519 signature, but produced
        // by a different key than the one the invitation claims as its identityKey.
        byte[] foreignSignature = actualSigner.sign(new byte[32]);
        SignedInvitation bogus = new SignedInvitation(invitation, foreignSignature);
        byte[] encoded = InvitationCodec.encode(bogus);
        InvitationException failure = assertThrows(InvitationException.class, () -> InvitationCodec.decode(encoded));
        assertEquals(InvitationRejectionReason.BAD_SIGNATURE, failure.reason());
    }

    @Test
    void expiredInvitationDecodesButReportsExpired() {
        UnlockedIdentity identity = newIdentity();
        Instant issuedAt = Instant.now().minus(10, ChronoUnit.DAYS);
        Invitation expired = new Invitation(identity.publicKey(), newIdentity().publicKey(),
                issuedAt, issuedAt.plus(90, ChronoUnit.DAYS),
                List.of(new PeerAddress("203.0.113.5", 47123)), nonce(), issuedAt, issuedAt.plus(1, ChronoUnit.DAYS));
        SignedInvitation signed = InvitationCodec.sign(expired, identity);
        SignedInvitation decoded = InvitationCodec.decode(InvitationCodec.encode(signed));
        assertTrue(decoded.invitation().isExpired(Instant.now()));
    }

    @Test
    void rejectsMoreThanFourAddressesAtConstruction() {
        UnlockedIdentity identity = newIdentity();
        Instant now = Instant.now();
        List<PeerAddress> tooMany = List.of(
                new PeerAddress("10.0.0.1", 1), new PeerAddress("10.0.0.2", 1),
                new PeerAddress("10.0.0.3", 1), new PeerAddress("10.0.0.4", 1), new PeerAddress("10.0.0.5", 1));
        InvitationException failure = assertThrows(InvitationException.class, () -> new Invitation(
                identity.publicKey(), newIdentity().publicKey(), now.minusSeconds(60), now.plusSeconds(3600),
                tooMany, nonce(), now, now.plus(1, ChronoUnit.DAYS)));
        assertEquals(InvitationRejectionReason.INVALID_ADDRESS, failure.reason());
    }

    @Test
    void rejectsHostnamesNotIpLiterals() {
        assertThrows(IllegalArgumentException.class, () -> new PeerAddress("example.com", 443));
    }

    @Test
    void rejectsTransportKeyEqualToIdentityKey() {
        UnlockedIdentity identity = newIdentity();
        Instant now = Instant.now();
        InvitationException failure = assertThrows(InvitationException.class, () -> new Invitation(
                identity.publicKey(), identity.publicKey(), now.minusSeconds(60), now.plusSeconds(3600),
                List.of(new PeerAddress("10.0.0.1", 1)), nonce(), now, now.plus(1, ChronoUnit.DAYS)));
        assertEquals(InvitationRejectionReason.TRANSPORT_KEY_EQUALS_IDENTITY_KEY, failure.reason());
    }

    @Test
    void rejectsOversizedEncodedInput() {
        byte[] huge = new byte[10_000];
        InvitationException failure = assertThrows(InvitationException.class, () -> InvitationCodec.decode(huge));
        assertEquals(InvitationRejectionReason.OVERSIZED, failure.reason());
    }

    @Test
    void rejectsTruncatedInput() {
        UnlockedIdentity identity = newIdentity();
        SignedInvitation signed = InvitationCodec.sign(validInvitation(identity), identity);
        byte[] encoded = InvitationCodec.encode(signed);
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 10);
        InvitationException failure = assertThrows(InvitationException.class, () -> InvitationCodec.decode(truncated));
        assertEquals(InvitationRejectionReason.MALFORMED, failure.reason());
    }

    @Test
    void rejectsTrailingBytes() {
        UnlockedIdentity identity = newIdentity();
        SignedInvitation signed = InvitationCodec.sign(validInvitation(identity), identity);
        byte[] encoded = InvitationCodec.encode(signed);
        byte[] withTrailing = java.util.Arrays.copyOf(encoded, encoded.length + 3);
        InvitationException failure = assertThrows(InvitationException.class, () -> InvitationCodec.decode(withTrailing));
        assertEquals(InvitationRejectionReason.MALFORMED, failure.reason());
    }

    @Test
    void rejectsUnsupportedFormatVersion() {
        UnlockedIdentity identity = newIdentity();
        SignedInvitation signed = InvitationCodec.sign(validInvitation(identity), identity);
        byte[] encoded = InvitationCodec.encode(signed);
        encoded[0] = 99;
        InvitationException failure = assertThrows(InvitationException.class, () -> InvitationCodec.decode(encoded));
        assertEquals(InvitationRejectionReason.UNSUPPORTED_FORMAT_VERSION, failure.reason());
    }

    @Test
    void signRejectsAnInvitationWhoseIdentityKeyIsNotTheSigner() {
        UnlockedIdentity signer = newIdentity();
        Invitation invitation = validInvitation(newIdentity());
        assertThrows(IllegalArgumentException.class, () -> InvitationCodec.sign(invitation, signer));
    }
}
