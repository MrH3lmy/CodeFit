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
     * The challenger's own inbox effect of receiving the opponent's {@code MatchResponse} (including
     * an opponent's resend of an already-accepted response, e.g. after the opponent's own match has
     * reached {@code COMPLETED} - see {@code PeerSyncOutboxService#buildMatchEnvelope}). Applied only
     * while the local row is still {@code PENDING}: once this device has itself applied a response
     * (now {@code ACTIVE}/{@code DECLINED}) a resend is simply the ordinary idempotent replay case, and
     * once this device has independently cancelled ({@code CANCELLED}) a later/replayed response must
     * never resurrect it. This is the challenger-side half of the same "cancellation always wins" rule
     * {@link #applyReceivedCancellation} documents in full; together they guarantee both devices
     * converge on {@code CANCELLED} regardless of which of the two in-flight messages each side
     * happens to see first.
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
     * targeting {@code MATCH_INVITATION}).
     *
     * <p><b>Conflict rule: a challenger cancellation always wins a response race.</b> {@code
     * recordLocalCancellation} only ever generates this message atomically from the challenger's own
     * still-{@code PENDING} state - there is no code path that produces a cancellation any later than
     * that. So by the time this device receives one, it is - by construction - a legitimate decision
     * made while the challenger still believed the match was PENDING, even if this device had already
     * raced ahead to {@code ACTIVE}/{@code DECLINED}/{@code COMPLETED} (e.g. accepted and even finished
     * studying) before the message arrived over a slow or reconnecting link. Applying it unconditionally
     * - over any current status, not just {@code PENDING} - is what makes both devices converge on the
     * same terminal outcome regardless of which order the acceptance/decline and the cancellation
     * happen to arrive in; see {@code applyReceivedResponse}'s own guard for the matching half of this
     * rule (a response can never resurrect an already-{@code CANCELLED} row on the challenger's own
     * side, since cancellation is exactly as terminal there).
     *
     * <p>The only no-op case is already-{@code CANCELLED} (or no such row at all): cancellation can only
     * be sent once per match, so re-applying it to itself is simply the ordinary idempotent replay case,
     * not a second, independent race.
     */
    public void applyReceivedCancellation(Connection connection, ObjectId matchId, Instant now) throws SQLException {
        Optional<StudyMatch> existing = findWithin(connection, matchId);
        if (existing.isEmpty() || existing.get().status() == MatchStatus.CANCELLED) {
            return;
        }
        setCancelled(connection, matchId, now);
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
     * {@code COMPLETED} is included for the {@code OPPONENT} role alongside {@code ACTIVE}: a match
     * reaching its own {@code endsAt} is purely a lazy local transition ({@code
     * completeIfPastEndsAt}) and must never stop the opponent from resending the very same accepted
     * {@code MatchResponse} the challenger may never have received before disconnecting - otherwise a
     * challenger who misses the original acceptance can be stranded in {@code PENDING} forever once
     * the opponent's own row has moved on. See {@code PeerSyncOutboxService#buildMatchEnvelope} for
     * the matching fix on the serialization side (COMPLETED must still encode {@code accepted=true}
     * with the original {@code startedAt}, never a decline).
     *
     * <p>Deliberately not gated by any "already delivered" hint (#184's own established "resend is
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
                             + "(role = 'OPPONENT' AND status IN ('ACTIVE', 'DECLINED', 'COMPLETED'))) ORDER BY created_at")) {
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

    /**
     * Moves to {@code CANCELLED} from any current status, clearing {@code accepted_at}/{@code
     * started_at}/{@code ends_at} so the row still satisfies {@link StudyMatch}'s own invariant
     * ("timed" fields set iff {@code ACTIVE}/{@code COMPLETED") even when cancelling a row that had
     * already progressed to {@code ACTIVE}/{@code DECLINED}/{@code COMPLETED}. Unlike {@link
     * #setStatus}, which is only ever used for a transition out of a state with no timing fields set.
     */
    private void setCancelled(Connection connection, ObjectId matchId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE study_matches SET status = ?, accepted_at = NULL, started_at = NULL, ends_at = NULL, updated_at = ? "
                        + "WHERE match_id = ?")) {
            statement.setString(1, MatchStatus.CANCELLED.name());
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
