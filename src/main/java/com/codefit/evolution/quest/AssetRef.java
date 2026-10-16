package com.codefit.evolution.quest;

import com.codefit.evolution.quest.QuestTypes.AssetKind;

import java.util.Objects;

/** Bundled local resource pointer. No URLs, absolute paths or traversal. */
public record AssetRef(String id, AssetKind kind, String path, String sha256) {
    public AssetRef {
        CatalogRef.id(id);
        Objects.requireNonNull(kind);
        if (path == null
                || !path.matches("evolution/packs/[a-zA-Z0-9_./-]+")
                || path.contains("..")
                || path.endsWith("/"))
            throw new IllegalArgumentException("Safe bundled asset path required");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("SHA-256 required");
    }
}
