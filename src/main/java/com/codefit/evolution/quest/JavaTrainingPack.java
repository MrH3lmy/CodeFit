package com.codefit.evolution.quest;

import com.codefit.repository.QuestCatalogRepository;

import java.sql.*;
import java.util.*;

/**
 * Original offline pack. Appending a version requires retaining every historical manifest and
 * asset.
 */
public final class JavaTrainingPack {
    private static final String ROOT = "evolution/packs/java-debugging-concurrency/v1/";

    private JavaTrainingPack() {}

    public static List<ChallengeSpec> challenges() {
        var assets = new BundledAssets();
        return assets.manifest(ROOT + "index.txt")
                .lines()
                .map(p -> CatalogCodec.challenge(assets.manifest(ROOT + p)))
                .toList();
    }

    public static List<EvolutionQuest> quests() {
        var assets = new BundledAssets();
        return assets.manifest(ROOT + "quests.txt")
                .lines()
                .map(p -> CatalogCodec.quest(assets.manifest(ROOT + p)))
                .toList();
    }

    public static void seed(Connection c) throws SQLException {
        new QuestCatalogRepository().publish(c, quests(), challenges());
    }
}
