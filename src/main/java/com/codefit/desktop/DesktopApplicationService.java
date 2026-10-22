package com.codefit.desktop;

import com.codefit.model.Deck;
import com.codefit.repository.DeckRepository;
import com.codefit.service.DeckService;

import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.sql.*;
import java.util.List;

/**
 * UI-independent allowlisted use cases. No adaptive, reward, exposure, runner or peer services
 * start.
 */
final class DesktopApplicationService {
    record State(String profileId, List<Deck> decks, Long nextAfterId) {}

    record Receipt(String commandId, String status, Deck deck) {}

    record CommandStatus(String commandId, Receipt receipt) {}

    private final ExperimentalProfile profile;

    DesktopApplicationService(ExperimentalProfile profile) {
        this.profile = profile;
    }

    State readState(long afterId) throws SQLException {
        try (Connection c = profile.connection(true)) {
            List<Deck> page = new DeckRepository().findPage(c, afterId, 9);
            boolean more = page.size() > 8;
            List<Deck> shown = List.copyOf(more ? page.subList(0, 8) : page);
            return new State(profile.id(), shown, more ? shown.getLast().getId() : null);
        }
    }

    CommandStatus status(String profileId, String commandId) throws SQLException {
        authorize(profileId);
        try (Connection c = profile.connection(true)) {
            return new CommandStatus(commandId, findReceipt(c, commandId));
        }
    }

    Receipt create(String profileId, String commandId, String name, String description)
            throws SQLException {
        authorize(profileId);
        if (!ApplicationProtocol.uuid(commandId)
                || !ApplicationProtocol.validText(name, 256)
                || name.isBlank()
                || !ApplicationProtocol.validText(description, 2048))
            throw new IllegalArgumentException("INVALID_INPUT");
        try (Connection c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                Receipt previous = findReceipt(c, commandId);
                if (previous != null) {
                    try (var s =
                            c.prepareStatement(
                                    "SELECT input_name, input_description FROM desktop_commands"
                                        + " WHERE command_id=?")) {
                        s.setString(1, commandId);
                        try (var r = s.executeQuery()) {
                            if (!r.next()
                                    || !name.equals(r.getString(1))
                                    || !description.equals(r.getString(2)))
                                throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                        }
                    }
                    c.rollback();
                    return previous;
                }
                Deck deck;
                try {
                    deck = new DeckService().createDeck(c, name, description);
                } catch (SQLiteException failure) {
                    if (failure.getResultCode() != SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)
                        throw failure;
                    deck = null; // Terminal semantic rejection is also durable and replayable.
                }
                Receipt receipt =
                        new Receipt(commandId, deck == null ? "rejected" : "created", deck);
                try (var s =
                        c.prepareStatement("INSERT INTO desktop_commands VALUES (?,?,?,?,?,?,?)")) {
                    s.setString(1, commandId);
                    s.setString(2, name);
                    s.setString(3, description);
                    s.setString(4, receipt.status());
                    if (deck == null) s.setNull(5, Types.INTEGER);
                    else s.setLong(5, deck.getId());
                    s.setString(6, deck == null ? null : deck.getName());
                    s.setString(7, deck == null ? null : deck.getDescription());
                    s.executeUpdate();
                }
                c.commit();
                return receipt;
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            }
        }
    }

    private void authorize(String profileId) {
        if (!profile.id().equals(profileId)) throw new IllegalArgumentException("PROFILE_MISMATCH");
    }

    private Receipt findReceipt(Connection c, String commandId) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT status, deck_id, deck_name, deck_description FROM desktop_commands"
                            + " WHERE command_id=?")) {
            s.setString(1, commandId);
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                String status = r.getString(1);
                return new Receipt(
                        commandId,
                        status,
                        "created".equals(status)
                                ? new Deck(r.getLong(2), r.getString(3), r.getString(4), null)
                                : null);
            }
        }
    }
}
