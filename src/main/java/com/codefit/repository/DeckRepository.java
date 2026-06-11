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
        String sql = "INSERT INTO decks (name, description) VALUES (?, ?)";
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, deck.getName());
            statement.setString(2, deck.getDescription());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    deck.setId(keys.getLong(1));
                }
            }
            return deck;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save deck", exception);
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
