package com.codefit.evolution.evidence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Length-delimited canonical identity, independent of adapters, maps, checks and ingestion time.
 */
public final class EvidenceIds {
    private EvidenceIds() {}

    public static String id(EvidenceSourceRef ref) {
        return identity(ref, true);
    }

    public static String root(EvidenceSourceRef ref) {
        return identity(ref, false);
    }

    private static String identity(EvidenceSourceRef ref, boolean revision) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeUTF("CodeFit.Evidence.v1");
                out.writeUTF(ref.type().name());
                out.writeLong(ref.recordId());
                out.writeUTF(ref.eventKind());
                if (revision) out.writeInt(ref.revision());
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
