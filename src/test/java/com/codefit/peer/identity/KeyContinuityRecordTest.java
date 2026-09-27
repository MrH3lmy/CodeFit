package com.codefit.peer.identity;

import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyContinuityRecordTest {

    @Test
    void aGenuineContinuityRecordVerifiesAgainstTheOldPublicKey() {
        KeyPair oldPair = KeyPairs.generate();
        KeyPair newPair = KeyPairs.generate();
        IdentityKey oldKey = new IdentityKey(KeyPairs.rawPublicKey(oldPair.getPublic()));
        IdentityKey newKey = new IdentityKey(KeyPairs.rawPublicKey(newPair.getPublic()));
        Instant rotatedAt = Instant.ofEpochMilli(1_735_000_000_000L);
        byte[] signature = KeyPairs.sign(oldPair.getPrivate(), KeyContinuityRecord.signingBytes(oldKey, newKey, rotatedAt));

        KeyContinuityRecord record = new KeyContinuityRecord(oldKey, newKey, rotatedAt, signature);

        assertTrue(record.verify());
    }

    @Test
    void aRecordSignedByTheWrongKeyDoesNotVerify() {
        KeyPair oldPair = KeyPairs.generate();
        KeyPair impostorPair = KeyPairs.generate();
        KeyPair newPair = KeyPairs.generate();
        IdentityKey oldKey = new IdentityKey(KeyPairs.rawPublicKey(oldPair.getPublic()));
        IdentityKey newKey = new IdentityKey(KeyPairs.rawPublicKey(newPair.getPublic()));
        Instant rotatedAt = Instant.ofEpochMilli(1_735_000_000_000L);
        byte[] signature = KeyPairs.sign(impostorPair.getPrivate(), KeyContinuityRecord.signingBytes(oldKey, newKey, rotatedAt));

        KeyContinuityRecord record = new KeyContinuityRecord(oldKey, newKey, rotatedAt, signature);

        assertFalse(record.verify());
    }
}
