package com.codefit.desktop;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.adaptation.PracticeEligibilityService;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Dedicated live owner only. Opening is the sole learning write; no grader/reward entry point. */
final class PracticeApplicationService {
    record Candidate(
            String questId,
            int questVersion,
            String challengeId,
            int challengeVersion,
            String title,
            int effortMinutes) {}

    record Receipt(
            String commandId,
            String presentationId,
            String questId,
            int questVersion,
            String challengeId,
            int challengeVersion,
            String presentedAt) {}

    record Opened(Receipt receipt, String instructions) {}

    private final ExperimentalProfile profile;
    private final String session = UUID.randomUUID().toString();
    private final Clock clock;
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final ChallengeExposureRepository exposures = new ChallengeExposureRepository();
    private final ChallengePresentationService presentations;
    private final boolean runnerAvailable;

    PracticeApplicationService(ExperimentalProfile profile, Clock clock) {
        this.profile = profile;
        this.clock = clock;
        this.presentations = new ChallengePresentationService(catalog, new BundledAssets(), clock);
        this.runnerAvailable = new JavaCodeRunner().isAvailable();
        // Dedicated process: every existing repository resolves the same exclusively owned DB.
        DatabaseConfig.useDatabaseFile(profile.database());
    }

    String session() {
        return session;
    }

    String profileId() {
        return profile.id();
    }

    void authorize(String profileId, String sessionId) {
        if (!profile.id().equals(profileId) || !session.equals(sessionId))
            throw new IllegalArgumentException("SESSION_MISMATCH");
    }

    List<Candidate> list() throws SQLException {
        try (var c = profile.connection(true)) {
            c.setAutoCommit(false);
            try {
                var list = candidates(c);
                c.commit();
                return list;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private List<Candidate> candidates(Connection c) throws SQLException {
        return new PracticeEligibilityService()
                .eligible(c, clock.instant(), runnerAvailable).stream()
                        .map(
                                x ->
                                        new Candidate(
                                                x.quest().id(),
                                                x.quest().version(),
                                                x.challenge().id(),
                                                x.challenge().version(),
                                                JavaTrainingPack.quests().stream()
                                                        .filter(q -> q.ref().equals(x.quest()))
                                                        .findFirst()
                                                        .orElseThrow()
                                                        .title(),
                                                x.effortMinutes()))
                        .toList();
    }

    Receipt status(String commandId) throws SQLException {
        requireCommand(commandId);
        try (var c = profile.connection(true)) {
            return exposures
                    .find(c, presentationId(commandId))
                    .map(p -> receipt(commandId, p))
                    .orElse(null);
        }
    }

    Opened open(String commandId, CatalogRef quest, CatalogRef challenge) throws SQLException {
        requireCommand(commandId);
        // Exact bundled PRACTICE identity checked even for replay. No draft or reserved IDs.
        var q =
                JavaTrainingPack.quests().stream()
                        .filter(
                                x ->
                                        x.ref().equals(quest)
                                                && x.cohort() == QuestTypes.Cohort.PRACTICE
                                                && x.challenges().contains(challenge))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("NOT_ELIGIBLE"));
        freezeCommand(commandId, quest, challenge);
        String id = presentationId(commandId);
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                catalog.lock(c);
                if (!catalog.findQuest(c, quest).filter(q::equals).isPresent())
                    throw new IllegalArgumentException("NOT_ELIGIBLE");
                var installed =
                        JavaTrainingPack.challenges().stream()
                                .filter(x -> x.ref().equals(challenge))
                                .findFirst()
                                .orElseThrow();
                if (!catalog.findChallenge(c, challenge).filter(installed::equals).isPresent())
                    throw new IllegalArgumentException("NOT_ELIGIBLE");
                var previous = exposures.find(c, id);
                if (previous.isPresent()) {
                    if (!previous.get().quest().equals(quest)
                            || !previous.get().challenge().equals(challenge))
                        throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                } else if (candidates(c).stream()
                        .noneMatch(
                                x ->
                                        x.questId().equals(quest.id())
                                                && x.questVersion() == quest.version()
                                                && x.challengeId().equals(challenge.id())
                                                && x.challengeVersion() == challenge.version()))
                    throw new IllegalArgumentException("NOT_ELIGIBLE");
                // A committed Start is a resume, never a repair/initialization command.
                // Missing drafts/sessions must not be silently recreated by replay.
                if (previous.isEmpty())
                    presentations.present(
                            c,
                            new ChallengePresentation.Request(
                                    id,
                                    quest,
                                    challenge,
                                    QuestTypes.Cohort.PRACTICE,
                                    QuestTypes.Familiarity.UNKNOWN));
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        // Existing service reads protected material ONLY after the commit above.
        var view = presentations.resume(id);
        if (view.prompt().length() > 24000) throw new IllegalStateException("CONTENT_UNAVAILABLE");
        return new Opened(receipt(commandId, view.receipt()), view.prompt());
    }

    /**
     * Freeze identifiers independently of exposure so failed/interrupted authorization is
     * replayable.
     */
    private void freezeCommand(String commandId, CatalogRef quest, CatalogRef challenge)
            throws SQLException {
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                catalog.lock(c);
                try (var s =
                        c.prepareStatement(
                                "SELECT quest_id,quest_version,challenge_id,challenge_version FROM"
                                        + " desktop_practice_commands WHERE command_id=?")) {
                    s.setString(1, commandId);
                    try (var r = s.executeQuery()) {
                        if (r.next()) {
                            if (!quest.id().equals(r.getString(1))
                                    || quest.version() != r.getInt(2)
                                    || !challenge.id().equals(r.getString(3))
                                    || challenge.version() != r.getInt(4))
                                throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                            c.commit();
                            return;
                        }
                    }
                }
                try (var s =
                        c.prepareStatement(
                                "INSERT INTO desktop_practice_commands VALUES (?,?,?,?,?)")) {
                    s.setString(1, commandId);
                    s.setString(2, quest.id());
                    s.setInt(3, quest.version());
                    s.setString(4, challenge.id());
                    s.setInt(5, challenge.version());
                    s.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static String presentationId(String command) {
        return "desktop-practice-" + command;
    }

    private static void requireCommand(String command) {
        if (!ApplicationProtocol.uuid(command)) throw new IllegalArgumentException("INVALID_INPUT");
    }

    private static Receipt receipt(String command, ChallengePresentation p) {
        return new Receipt(
                command,
                p.id(),
                p.quest().id(),
                p.quest().version(),
                p.challenge().id(),
                p.challenge().version(),
                p.presentedAt().toString());
    }
}
