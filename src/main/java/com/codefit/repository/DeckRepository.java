package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.Deck;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DeckRepository {
    public List<Deck> findAll() {
        String sql = "SELECT id, name, description, created_at FROM decks ORDER BY name";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Deck> decks = new ArrayList<>();
            while (resultSet.next()) {
                decks.add(mapDeck(resultSet));
            }
            return decks;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load decks", exception);
        }
    }

    public Optional<Deck> findById(long id) {
        String sql = "SELECT id, name, description, created_at FROM decks WHERE id = ?";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapDeck(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load deck", exception);
        }
    }

    public Deck save(Deck deck) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return save(connection, deck);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save deck", exception);
        }
    }

    /** Caller owns the connection and transaction; this method never commits or closes it. */
    public Deck save(Connection connection, Deck deck) throws SQLException {
        String sql = "INSERT INTO decks (name, description) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, deck.getName());
            statement.setString(2, deck.getDescription());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    deck.setId(keys.getLong(1));
                }
            }
            return deck;
        }
    }

    /** Bounded, stable descriptor projection. No content, reconciliation, or writes. */
    public List<Deck> findPage(Connection connection, long afterId, int limit) throws SQLException {
        if (afterId < 0 || limit < 1 || limit > 9) throw new IllegalArgumentException();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, CASE WHEN length(name) <= 256 THEN name ELSE NULL END AS name, "
                        + "CASE WHEN length(description) <= 2048 THEN description ELSE NULL END AS description, "
                        + "substr(created_at, 1, 64) AS created_at FROM decks WHERE id > ? ORDER BY id LIMIT ?")) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<Deck> result = new ArrayList<>();
                while (rows.next()) result.add(mapDeck(rows));
                return result;
            }
        }
    }

    private Deck mapDeck(ResultSet resultSet) throws SQLException {
        return new Deck(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("description"),
                LocalDateTime.parse(resultSet.getString("created_at").replace(' ', 'T'))
        );
    }
}
