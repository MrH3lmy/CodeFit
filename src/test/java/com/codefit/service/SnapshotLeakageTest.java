package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.EnvelopeCodec;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #183 requirement (7)/(20): concrete, content-level proof that private learning material never
 * reaches the wire - not just that the wire *types* have no field for it (see
 * {@code com.codefit.peer.protocol.PeerVisibleContractTest}), but that real secret strings planted in
 * the exact DB columns a #183 snapshot is built from do not survive into the actual encoded, signed
 * envelope bytes a peer would receive.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SnapshotLeakageTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private static final String SECRET_FLASHCARD_FRONT = "SECRET_FRONT_f3a9c1";
    private static final String SECRET_FLASHCARD_BACK = "SECRET_BACK_2b7e40";
    private static final String SECRET_HINT = "SECRET_HINT_8d41aa";
    private static final String SECRET_ACCEPTED_ANSWER = "SECRET_ANSWER_55d0e2";
    private static final String SECRET_SUBMITTED_ANSWER = "SECRET_SUBMITTED_6c19ab";
    private static final String SECRET_PROBLEM_TITLE = "SECRET_PROBLEM_TITLE_a1b2c3";
    private static final String SECRET_PROBLEM_URL = "https://secret-resource.example/a1b2c3";
    private static final String SECRET_LEARNING_RESOURCE = "SECRET_EDITORIAL_LINK_d4e5f6";
    private static final String SECRET_ATTEMPT_NOTE = "SECRET_PRIVATE_NOTE_778899";
    private static final String SECRET_DECK_DESCRIPTION = "SECRET_DECK_DESCRIPTION_aabbcc";

    private final ContactService contactService = new ContactService();
    private final IdentityService identityService = new IdentityService();
    private SnapshotPublicationService publicationService;
    private UnlockedIdentity identity;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        try (Connection connection = DatabaseConfig.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM review_history");
            statement.execute("DELETE FROM problem_attempts");
            statement.execute("DELETE FROM problem_progress");
            statement.execute("DELETE FROM problems");
            statement.execute("DELETE FROM flashcards");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        identityService.createIdentity("vault-pass".toCharArray(), BASE);
        identityService.resumeSharingAfterReview();
        identity = identityService.unlock("vault-pass".toCharArray());
        publicationService = new SnapshotPublicationService();
    }

    private static IdentityKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new IdentityKey(bytes);
    }

    private long createSecretDeckAndFlashcard() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection()) {
            long deckId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES (?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "secret-deck-" + System.nanoTime());
                statement.setString(2, SECRET_DECK_DESCRIPTION);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    keys.next();
                    deckId = keys.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, hint, review_count, due_date) "
                            + "VALUES (?, ?, ?, 'RECALL', ?, ?, 0, date('now'))",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, deckId);
                statement.setString(2, SECRET_FLASHCARD_FRONT);
                statement.setString(3, SECRET_FLASHCARD_BACK);
                statement.setString(4, SECRET_ACCEPTED_ANSWER);
                statement.setString(5, SECRET_HINT);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        }
    }

    @Test
    void noPrivateLearningContentSurvivesIntoTheSignedWireBytes() throws SQLException {
        LocalDate day = LocalDate.of(2026, 1, 15);
        long flashcardId = createSecretDeckAndFlashcard();
        ReviewHistory review = new ReviewHistoryRepository().save(new ReviewHistory(0, flashcardId, ReviewRating.GOOD,
                0, 1, day.atTime(10, 0), true, false, "EXACT", SECRET_SUBMITTED_ANSWER, null, false, null, null));
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE review_history SET reviewed_at = ? WHERE id = ?")) {
            statement.setString(1, day.atTime(10, 0).toString());
            statement.setLong(2, review.getId());
            statement.executeUpdate();
        }

        Problem problem = new ProblemRepository().save(new Problem("SECRET-P1", "JUNIOR", SECRET_PROBLEM_TITLE,
                SECRET_PROBLEM_URL, "General", null, SECRET_LEARNING_RESOURCE));
        ProblemAttempt attempt = new ProblemAttemptRepository().save(new ProblemAttempt(0, problem.getId(), 1,
                SubmissionResult.AC, 60, 60, 60, 60, day.atTime(11, 0), SECRET_ATTEMPT_NOTE));
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE problem_attempts SET submitted_at = ? WHERE id = ?")) {
            statement.setString(1, day.atTime(11, 0).toString());
            statement.setLong(2, attempt.id());
            statement.executeUpdate();
        }
        new com.codefit.repository.ProblemProgressRepository().save(new ProblemProgress(0, problem.getId(),
                ProblemState.SOLVED, null, SolvedWith.SELF, null, null, null, null, null, null, null, null,
                false, false, false, false, day.atTime(11, 0), day.atTime(11, 0)));

        Contact contact = contactService.registerPendingContact(key(42), "Nora", BASE);
        contact = contactService.acceptInvitation(contact.id(), BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);

        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        SignedEnvelope envelope = publicationService.publishProgressSummary(contact.id(), identity, 1, window);

        String wire = rawBytesAsLatin1(EnvelopeCodec.encodeFrame(envelope));

        // Sanity check the search itself is meaningful: a legitimately-wire-visible metric id must be found.
        assertTrue(wire.contains("review.attempts"), "sanity check: a real, approved wire field must be found by this search");

        for (String secret : List.of(SECRET_FLASHCARD_FRONT, SECRET_FLASHCARD_BACK, SECRET_HINT, SECRET_ACCEPTED_ANSWER,
                SECRET_SUBMITTED_ANSWER, SECRET_PROBLEM_TITLE, SECRET_PROBLEM_URL, SECRET_LEARNING_RESOURCE,
                SECRET_ATTEMPT_NOTE, SECRET_DECK_DESCRIPTION)) {
            assertFalse(wire.contains(secret), "private content leaked onto the wire: " + secret);
        }
    }

    @Test
    void noPrivateContentSurvivesIntoAPreparationSnapshotEnvelope() {
        Contact contact = contactService.registerPendingContact(key(43), "Omar", BASE);
        contact = contactService.acceptInvitation(contact.id(), BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.PREPARATION_SNAPSHOT), null, null, false), BASE);
        String profileId = new InterviewProfileService().getRevolutJavaProfile().getId();

        SignedEnvelope envelope = publicationService.publishPreparationSnapshot(contact.id(), identity, 1, profileId);
        String wire = rawBytesAsLatin1(EnvelopeCodec.encodeFrame(envelope));

        assertTrue(wire.contains(profileId), "sanity check: the profile id itself is legitimately on the wire");
        for (String secret : List.of(SECRET_FLASHCARD_FRONT, SECRET_HINT, SECRET_ACCEPTED_ANSWER, SECRET_PROBLEM_TITLE,
                SECRET_PROBLEM_URL, SECRET_DECK_DESCRIPTION)) {
            assertFalse(wire.contains(secret), "private content leaked onto a preparation snapshot envelope: " + secret);
        }
    }

    /** ISO-8859-1 maps every byte value 1:1 to one char, so no secret ASCII substring can be hidden by decoding. */
    private static String rawBytesAsLatin1(byte[] bytes) {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        copy.writeBytes(bytes);
        return new String(copy.toByteArray(), StandardCharsets.ISO_8859_1);
    }
}
