package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The local lifecycle table for 1-v-1 Study Matches (see {@link StudyMatch}'s own javadoc for why
 * one row serves both roles). Two call shapes:
 *
 * <ul>
 *   <li><b>Local actions</b> ({@code createChallengerInvitation}, {@code recordLocalResponse},
 *       {@code recordLocalCancellation}) - this device's own user-driven mutations, each opening its
 *       own connection. Callers ({@code PeerMatchService}) must hold {@code PeerLocalWriteLock
 *       .MONITOR} around these, exactly like every other local write that can now run concurrently
 *       with an established connection's automatic receive loop.</li>
 *   <li><b>Received actions</b> ({@code applyReceived*}) - take the caller's own {@link Connection},
 *       for use only from inside {@code PeerSyncIngestService}'s single ingest transaction (already
 *       running under that same lock). These are deliberately lenient: an out-of-order, duplicate, or
 *       terminal-state-violating message is silently ignored rather than thrown, because there is no
 *       user waiting on feedback for a message that arrived off the wire - see each method's own
 *       javadoc for exactly which states it accepts.</li>
 * </ul>
 */
public class MatchRepository {

    public StudyMatch createChallengerInvitation(long contactId, ObjectId matchId, MatchDuration duration, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO study_matches (match_id, contact_id, role, duration_minutes, status, created_at, updated_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setBytes(1, matchId.bytes());
            statement.setLong(2, contactId);
            statement.setString(3, MatchRole.CHALLENGER.name());
            statement.setInt(4, duration.minutes());
            statement.setString(5, MatchStatus.PENDING.name());
            statement.setString(6, now.toString());
            statement.setString(7, now.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create match invitation", exception);
        }
        return find(matchId).orElseThrow();
    }

    /**
     * The opponent's own accept/decline action. Requires the current local status to be
     * {@code PENDING}; any other current status throws, since this is a direct, user-initiated
     * action that must never silently no-op (the UI needs to know "you already responded" rather
     * than appear to do nothing).
     */
    public StudyMatch recordLocalResponse(ObjectId matchId, boolean accepted, Instant startedAt, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            StudyMatch current = findWithin(connection, matchId)
                    .orElseThrow(() -> new IllegalStateException("No such match."));
            if (current.status() != MatchStatus.PENDING) {
                throw new IllegalStateException("Match " + matchId + " already left PENDING (now " + current.status() + ").");
            }
            MatchStatus newStatus = accepted ? MatchStatus.ACTIVE : MatchStatus.DECLINED;
            Instant endsAt = accepted ? startedAt.plusSeconds(current.duration().minutes() * 60L) : null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE study_matches SET status = ?, accepted_at = ?, started_at = ?, ends_at = ?, updated_at = ? "
                            + "WHERE match_id = ?")) {
                statement.setString(1, newStatus.name());
                statement.setString(2, accepted ? now.toString() : null);
                statement.setString(3, accepted ? startedAt.toString() : null);
                statement.setString(4, accepted ? endsAt.toString() : null);
                statement.setString(5, now.toString());
                statement.setBytes(6, matchId.bytes());
                statement.executeUpdate();
            }
            return findWithin(connection, matchId).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to record match response", exception);
        }
    }

    /** The challenger's own withdrawal. Requires the current local status to be {@code PENDING}. */
    public StudyMatch recordLocalCancellation(ObjectId matchId, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            StudyMatch current = findWithin(connection, matchId)
                    .orElseThrow(() -> new IllegalStateException("No such match."));
            if (current.status() != MatchStatus.PENDING) {
                throw new IllegalStateException("Match " + matchId + " already left PENDING (now " + current.status() + ").");
            }
            setStatus(connection, matchId, MatchStatus.CANCELLED, now);
            return findWithin(connection, matchId).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to cancel match", exception);
        }
    }

    /**
     * The opponent's own inbox effect of receiving a brand-new {@code MatchInvitation}. Idempotent:
     * if a row for this {@code matchId} already exists (replay, or - defensively - some other local
     * origin), this is a no-op rather than overwriting whatever local state already exists.
     */
    public void applyReceivedInvitation(Connection connection, long contactId, ObjectId matchId, MatchDuration duration,
                                         Instant now) throws SQLException {
        if (findWithin(connection, matchId).isPresent()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO study_matches (match_id, contact_id, role, duration_minutes, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setBytes(1, matchId.bytes());
            statement.setLong(2, contactId);
            statement.setString(3, MatchRole.OPPONENT.name());
            statement.setInt(4, duration.minutes());
            statement.setString(5, MatchStatus.PENDING.name());
            statement.setString(6, now.toString());
            statement.setString(7, now.toString());
            statement.executeUpdate();
        }
    }

    /**
     * The challenger's own inbox effect of receiving the opponent's {@code MatchResponse}. Applied
     * only while the local row is still {@code PENDING} - a response arriving after this device's
     * own cancellation (a narrow, legitimate race between two in-flight messages) must never regress
     * an already-{@code CANCELLED} match back to {@code ACTIVE}/{@code DECLINED}.
     */
    public void applyReceivedResponse(Connection connection, ObjectId matchId, boolean accepted, Instant startedAt,
                                       Instant now) throws SQLException {
        Optional<StudyMatch> existing = findWithin(connection, matchId);
        if (existing.isEmpty() || existing.get().status() != MatchStatus.PENDING) {
            return;
        }
        StudyMatch current = existing.get();
        MatchStatus newStatus = accepted ? MatchStatus.ACTIVE : MatchStatus.DECLINED;
        Instant endsAt = accepted ? startedAt.plusSeconds(current.duration().minutes() * 60L) : null;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE study_matches SET status = ?, accepted_at = ?, started_at = ?, ends_at = ?, updated_at = ? "
                        + "WHERE match_id = ?")) {
            statement.setString(1, newStatus.name());
            statement.setString(2, accepted ? now.toString() : null);
            statement.setString(3, accepted ? startedAt.toString() : null);
            statement.setString(4, accepted ? endsAt.toString() : null);
            statement.setString(5, now.toString());
            statement.setBytes(6, matchId.bytes());
            statement.executeUpdate();
        }
    }

    /**
     * The opponent's own inbox effect of receiving the challenger's cancellation ({@code Tombstone}
     * targeting {@code MATCH_INVITATION}). Applied only while still {@code PENDING} - the same
     * terminal-state-must-not-regress protection as {@link #applyReceivedResponse}.
     */
    public void applyReceivedCancellation(Connection connection, ObjectId matchId, Instant now) throws SQLException {
        Optional<StudyMatch> existing = findWithin(connection, matchId);
        if (existing.isEmpty() || existing.get().status() != MatchStatus.PENDING) {
            return;
        }
        setStatus(connection, matchId, MatchStatus.CANCELLED, now);
    }

    /** Lazily moves an {@code ACTIVE} match whose {@code endsAt} has passed to {@code COMPLETED}. */
    public StudyMatch completeIfPastEndsAt(ObjectId matchId, Instant now) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            StudyMatch current = findWithin(connection, matchId).orElseThrow(() -> new IllegalStateException("No such match."));
            if (current.status() == MatchStatus.ACTIVE && !now.isBefore(current.endsAt())) {
                setStatus(connection, matchId, MatchStatus.COMPLETED, now);
                return findWithin(connection, matchId).orElseThrow();
            }
            return current;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to complete match", exception);
        }
    }

    public Optional<StudyMatch> find(ObjectId matchId) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return findWithin(connection, matchId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load match", exception);
        }
    }

    public List<StudyMatch> findByContact(long contactId) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM study_matches WHERE contact_id = ? ORDER BY created_at DESC")) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StudyMatch> matches = new ArrayList<>();
                while (resultSet.next()) {
                    matches.add(map(resultSet));
                }
                return matches;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load matches for contact", exception);
        }
    }

    /**
     * The lifecycle messages still worth sending {@code contactId}: a challenger's own still-live
     * proposal or withdrawal, and an opponent's own still-fresh response - see {@code
     * PeerSyncOutboxService}'s own javadoc for exactly which body each (role, status) pair becomes.
     * Deliberately not gated by any "already delivered" hint (#184's own established "resend is
     * always harmless" design, which {@code publication_outbox} itself already relies on) - a
     * resend is idempotent at the receiver via the generic replay-state check, and matches are few
     * enough per contact that resending a handful of tiny control envelopes on every reconnect is
     * negligible.
     */
    public List<StudyMatch> pendingOutboundFor(long contactId) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM study_matches WHERE contact_id = ? AND ("
                             + "(role = 'CHALLENGER' AND status IN ('PENDING', 'CANCELLED')) OR "
                             + "(role = 'OPPONENT' AND status IN ('ACTIVE', 'DECLINED'))) ORDER BY created_at")) {
            statement.setLong(1, contactId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StudyMatch> matches = new ArrayList<>();
                while (resultSet.next()) {
                    matches.add(map(resultSet));
                }
                return matches;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load pending outbound matches", exception);
        }
    }

    private void setStatus(Connection connection, ObjectId matchId, MatchStatus status, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE study_matches SET status = ?, updated_at = ? WHERE match_id = ?")) {
            statement.setString(1, status.name());
            statement.setString(2, now.toString());
            statement.setBytes(3, matchId.bytes());
            statement.executeUpdate();
        }
    }

    private Optional<StudyMatch> findWithin(Connection connection, ObjectId matchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM study_matches WHERE match_id = ?")) {
            statement.setBytes(1, matchId.bytes());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private StudyMatch map(ResultSet resultSet) throws SQLException {
        String acceptedAt = resultSet.getString("accepted_at");
        String startedAt = resultSet.getString("started_at");
        String endsAt = resultSet.getString("ends_at");
        return new StudyMatch(
                new ObjectId(resultSet.getBytes("match_id")),
                resultSet.getLong("contact_id"),
                MatchRole.valueOf(resultSet.getString("role")),
                MatchDuration.ofMinutes(resultSet.getInt("duration_minutes")),
                MatchStatus.valueOf(resultSet.getString("status")),
                Instant.parse(resultSet.getString("created_at")),
                acceptedAt == null ? null : Instant.parse(acceptedAt),
                startedAt == null ? null : Instant.parse(startedAt),
                endsAt == null ? null : Instant.parse(endsAt),
                Instant.parse(resultSet.getString("updated_at")));
    }
}
