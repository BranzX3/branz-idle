package dev.branzx.idlefarm.storage;

import dev.branzx.idlefarm.IdleFarmPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for extensible scoped gameplay state.
 *
 * <p>The cache is the runtime authority and every queued write captures an
 * immutable key/value snapshot. Keeping this concern outside the gameplay
 * orchestrator makes new commissions, achievements and seasonal state
 * testable without growing one service indefinitely.</p>
 */
public final class GameStateStore {

    private record Key(UUID owner, String scope, String scopeId, String name) {
    }

    /**
     * Immutable staged row for cross-aggregate transactions. Build one with
     * {@link #stage} (cache-first, for ordered async transactions) or the
     * record constructor plus {@link #applyCommitted} (for blocking
     * transactions that must not surface state until the commit succeeds).
     */
    public record Row(UUID owner, String scope, String scopeId, String name, String value) {
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final Map<Key, String> values = new ConcurrentHashMap<>();

    public GameStateStore(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, scope, scope_id, state_key, value_text "
                             + "FROM idlefarm_game_state");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                values.put(new Key(UUID.fromString(rs.getString("owner_uuid")),
                                rs.getString("scope"), rs.getString("scope_id"),
                                rs.getString("state_key")),
                        rs.getString("value_text"));
            }
        }
    }

    public String get(UUID owner, String scope, String scopeId, String name) {
        return values.get(new Key(owner, scope, scopeId, name));
    }

    public int getInt(UUID owner, String scope, String scopeId, String name, int fallback) {
        try {
            String value = get(owner, scope, scopeId, name);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public long getLong(UUID owner, String scope, String scopeId, String name, long fallback) {
        try {
            String value = get(owner, scope, scopeId, name);
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public void put(UUID owner, String scope, String scopeId, String name, String value) {
        Key key = new Key(owner, scope, scopeId, name);
        values.put(key, value);
        persist(key, value);
    }

    /**
     * Updates the runtime cache and returns the row without scheduling a
     * standalone write. The caller must persist the row with {@link #write}
     * inside the same transaction that settles the related aggregates.
     */
    public Row stage(UUID owner, String scope, String scopeId, String name, String value) {
        values.put(new Key(owner, scope, scopeId, name), value);
        return new Row(owner, scope, scopeId, name, value);
    }

    /**
     * Builds a row without exposing it through the runtime cache. Use this for
     * blocking cross-aggregate transactions, then call {@link #applyCommitted}
     * only after the database commit succeeds.
     */
    public Row prepare(UUID owner, String scope, String scopeId, String name, String value) {
        return new Row(owner, scope, scopeId, name, value);
    }

    /** Cache-first staged variant of {@link #increment}. */
    public synchronized Row stageIncrement(UUID owner, String scope, String scopeId,
                                           String name, long amount) {
        long next = getLong(owner, scope, scopeId, name, 0) + amount;
        return stage(owner, scope, scopeId, name, String.valueOf(next));
    }

    /** Non-mutating counterpart of {@link #stageIncrement}. */
    public synchronized Row prepareIncrement(UUID owner, String scope, String scopeId,
                                             String name, long amount) {
        long next = getLong(owner, scope, scopeId, name, 0) + amount;
        return prepare(owner, scope, scopeId, name, String.valueOf(next));
    }

    /** Applies a row committed by a blocking transaction to the runtime cache. */
    public void applyCommitted(Row row) {
        values.put(new Key(row.owner(), row.scope(), row.scopeId(), row.name()), row.value());
    }

    /** Persists one staged row on the caller's transaction connection. */
    public static void write(Connection connection, Row row) throws SQLException {
        try (PreparedStatement upsert = connection.prepareStatement(
                "REPLACE INTO idlefarm_game_state "
                        + "(owner_uuid, scope, scope_id, state_key, value_text, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
            upsert.setString(1, row.owner().toString());
            upsert.setString(2, row.scope());
            upsert.setString(3, row.scopeId());
            upsert.setString(4, row.name());
            upsert.setString(5, row.value());
            upsert.executeUpdate();
        }
    }

    public synchronized long increment(UUID owner, String scope, String scopeId,
                                       String name, long amount) {
        long next = getLong(owner, scope, scopeId, name, 0) + amount;
        put(owner, scope, scopeId, name, String.valueOf(next));
        return next;
    }

    public boolean claimOnce(UUID owner, String scope, String scopeId, String name) {
        Key key = new Key(owner, scope, scopeId, name);
        if (values.putIfAbsent(key, "1") != null) return false;
        persist(key, "1");
        return true;
    }

    public boolean containsValue(String scope, String name, String expectedValue) {
        return values.entrySet().stream().anyMatch(entry ->
                scope.equals(entry.getKey().scope())
                        && name.equals(entry.getKey().name())
                        && expectedValue.equals(entry.getValue()));
    }

    public String findByScopeId(String scope, String scopeId, String name) {
        return values.entrySet().stream()
                .filter(entry -> scope.equals(entry.getKey().scope())
                        && scopeId.equals(entry.getKey().scopeId())
                        && name.equals(entry.getKey().name()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private void persist(Key key, String value) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_game_state "
                                 + "(owner_uuid, scope, scope_id, state_key, value_text, updated_at) "
                                 + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                upsert.setString(1, key.owner().toString());
                upsert.setString(2, key.scope());
                upsert.setString(3, key.scopeId());
                upsert.setString(4, key.name());
                upsert.setString(5, value);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist game state: " + e.getMessage());
            }
        });
    }
}
