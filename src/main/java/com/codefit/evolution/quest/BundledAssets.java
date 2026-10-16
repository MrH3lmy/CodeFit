package com.codefit.evolution.quest;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Offline-only immutable resource reader. Does not execute or grade anything. */
public class BundledAssets {
    public String read(AssetRef asset) {
        byte[] bytes = resource(asset.path());
        if (!CatalogCodec.digest(bytes).equals(asset.sha256()))
            throw new IllegalStateException("Asset digest mismatch: " + asset.path());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public void validate(ChallengeSpec challenge) {
        challenge.assets().forEach(this::read);
    }

    public String manifest(String path) {
        return new String(resource(path), StandardCharsets.UTF_8);
    }

    private byte[] resource(String path) {
        if (!path.matches("evolution/packs/[a-zA-Z0-9_./-]+") || path.contains(".."))
            throw new IllegalArgumentException("Unsafe resource path");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource: " + path);
            byte[] bytes = in.readNBytes(200001);
            if (bytes.length > 200000) throw new IllegalArgumentException("Oversized resource");
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled asset", e);
        }
    }
}
