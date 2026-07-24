package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player identity and Coin access. Identity and playtime live in
 * {@code idle_players}; Coin lives in the shared {@code wallet_accounts} owned
 * by BranzWallet, in the same database so a coin write can commit inside the
 * gameplay transaction it pays for.
 *
 * <p><b>The Coin database row is authoritative — never the cache.</b> Coins are
 * shared with other backends and the Discord storefront, so a grant can land at
 * any moment. Every mutation is therefore a relative, floor-guarded write that
 * persists immediately, and {@link PlayerData#getBalance()} is only a display
 * cache: it is read on join and kept in step by applying the same delta, but it
 * is never written back. Snapshots deliberately do not touch coins — writing a
 * whole balance from a cached snapshot is exactly how a concurrent grant would
 * be erased.
 *
 * <p>Coins are whole numbers; deltas are rounded once, here at the boundary, and
 * the same rounded value is applied to both the row and the cache so the two
 * cannot drift.
 */
public final class PlayerDataStore {

    private final IdlePlugin plugin;
    private final Database database;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();

    public PlayerDataStore(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public PlayerData loadOrCreateSync(UUID uuid, String name) {
        try (Connection connection = database.getConnection()) {
            ensureWalletRow(connection, uuid, name);

            Long minutes = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT total_online_minutes FROM idle_players WHERE uuid = ?")) {
                select.setString(1, uuid.toString());
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        minutes = rs.getLong("total_online_minutes");
                    }
                }
            }
            if (minutes == null) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO idle_players (uuid, name, balance, total_online_minutes) VALUES (?, ?, 0, 0)")) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, name);
                    insert.executeUpdate();
                }
                minutes = 0L;
            }

            long coins = readCoins(connection, uuid);
            PlayerData data = new PlayerData(uuid, name, coins, minutes);
            online.put(uuid, data);
            return data;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load player data for " + uuid + ": " + e.getMessage());
            PlayerData fallback = new PlayerData(uuid, name, 0, 0);
            online.put(uuid, fallback);
            return fallback;
        }
    }

    /** Persists identity and playtime. Coins are never part of a snapshot. */
    public void saveSync(PlayerData data) {
        long revision = data.getRevision();
        UUID uuid = data.getUuid();
        String name = data.getName();
        long minutes = data.getTotalOnlineMinutes();
        boolean committed = database.executeTransaction("save player " + uuid,
                connection -> writeIdentity(connection, uuid, name, minutes));
        if (committed) data.markPersisted(revision);
    }

    /**
     * Queues an identity/playtime snapshot behind all preceding gameplay writes.
     * Revision checking prevents a later mutation from being marked clean by an
     * older snapshot completing asynchronously.
     */
    public void saveAsync(PlayerData data) {
        if (data == null || !data.isDirty()) return;
        long revision = data.getRevision();
        String name = data.getName();
        long minutes = data.getTotalOnlineMinutes();
        UUID uuid = data.getUuid();
        database.submitWrite(() -> saveSnapshot(uuid, name, minutes, data, revision));
    }

    public void saveAllDirtyAsync() {
        online.values().forEach(this::saveAsync);
    }

    private void saveSnapshot(UUID uuid, String name, long minutes,
                              PlayerData source, long revision) {
        try (Connection connection = database.getConnection()) {
            writeIdentity(connection, uuid, name, minutes);
            source.markPersisted(revision);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save player data for " + uuid + ": " + e.getMessage());
        }
    }

    public void unload(UUID uuid) {
        PlayerData data = online.remove(uuid);
        if (data != null) {
            saveSync(data);
        }
    }

    public void saveAllOnlineSync() {
        for (PlayerData data : online.values()) {
            saveSync(data);
        }
    }

    public PlayerData getOnline(UUID uuid) {
        return online.get(uuid);
    }

    public Collection<PlayerData> getAllOnline() {
        return online.values();
    }

    // ---- cross-server safe money access ----

    /**
     * Balance for any player. The cache answers for a player online here — it is
     * kept in step with every mutation this server makes — otherwise the shared
     * row is read directly.
     */
    public double balanceOf(UUID uuid) {
        PlayerData cached = online.get(uuid);
        if (cached != null) {
            return cached.getBalance();
        }
        try (Connection connection = database.getConnection()) {
            return readCoins(connection, uuid);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read balance for " + uuid + ": " + e.getMessage());
            return 0;
        }
    }

    public boolean accountExists(UUID uuid) {
        if (online.containsKey(uuid)) {
            return true;
        }
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT 1 FROM idle_players WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check account for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /** Creates the rows for a player who has never been seen. */
    public boolean createAccount(UUID uuid, String name) {
        if (accountExists(uuid)) {
            return false;
        }
        return database.executeTransaction("create account " + uuid, connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idle_players (uuid, name, balance, total_online_minutes) "
                            + "VALUES (?, ?, 0, 0)")) {
                insert.setString(1, uuid.toString());
                insert.setString(2, name == null ? uuid.toString() : name);
                insert.executeUpdate();
            }
            ensureWalletRow(connection, uuid, name == null ? uuid.toString() : name);
        });
    }

    /**
     * Fire-and-forget Coin change that persists immediately as a relative write
     * and keeps the cache in step. Use it for rewards and grants — payouts, admin
     * gifts, design rewards — that are not already part of a gameplay
     * transaction. Ordered behind preceding writes; never blocks the caller.
     */
    public void addCoins(UUID uuid, long delta) {
        if (delta == 0) {
            return;
        }
        PlayerData cached = online.get(uuid);
        if (cached != null) {
            cached.addBalance(delta);
        }
        String name = cached == null ? null : cached.getName();
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                ensureWalletRow(connection, uuid, name);
                if (delta > 0) {
                    CoinSql.credit(connection, uuid, delta);
                } else {
                    CoinSql.debit(connection, uuid, -delta);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to apply Coin delta " + delta
                        + " for " + uuid + ": " + e.getMessage());
            }
        });
    }

    /** Adds to any player's balance. Blocking; reports whether it committed. */
    public boolean deposit(UUID uuid, double amount) {
        long delta = Math.round(amount);
        if (delta <= 0) {
            return false;
        }
        boolean committed = database.executeTransaction("deposit " + uuid,
                connection -> CoinSql.credit(connection, uuid, delta));
        if (committed) {
            PlayerData cached = online.get(uuid);
            if (cached != null) cached.addBalance(delta);
        }
        return committed;
    }

    /**
     * Removes from any player's balance, refusing to overdraw. The sufficiency
     * check is part of the UPDATE, so two servers spending the same Coins cannot
     * both succeed.
     */
    public boolean withdraw(UUID uuid, double amount) {
        long delta = Math.round(amount);
        if (delta <= 0) {
            return false;
        }
        boolean committed = database.executeTransaction("withdraw " + uuid,
                connection -> CoinSql.debit(connection, uuid, delta));
        if (committed) {
            PlayerData cached = online.get(uuid);
            if (cached != null) cached.addBalance(-delta);
        }
        return committed;
    }

    public List<PlayerData> loadTopSync(int limit, int minMinutes) {
        List<PlayerData> result = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT p.uuid, p.name, COALESCE(w.coins, 0) AS coins, p.total_online_minutes "
                             + "FROM idle_players p LEFT JOIN wallet_accounts w ON p.uuid = w.uuid "
                             + "WHERE p.total_online_minutes >= ? ORDER BY coins DESC LIMIT ?")) {
            select.setInt(1, minMinutes);
            select.setInt(2, limit);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    result.add(new PlayerData(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            rs.getLong("coins"),
                            rs.getLong("total_online_minutes")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load top players: " + e.getMessage());
        }
        return result;
    }

    // ---- shared helpers ----

    /** Reads the whole-Coin balance from the shared wallet table (0 if none). */
    private long readCoins(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT coins FROM wallet_accounts WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getLong("coins") : 0;
            }
        }
    }

    /** Creates the wallet row the first time a player is seen. Safe to race. */
    private void ensureWalletRow(Connection connection, UUID uuid, String name) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                (database.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                        + " INTO wallet_accounts (uuid, name, coins) VALUES (?, ?, 0)")) {
            insert.setString(1, uuid.toString());
            insert.setString(2, name == null ? uuid.toString() : name);
            insert.executeUpdate();
        }
    }

    private void writeIdentity(Connection connection, UUID uuid, String name, long minutes)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE idle_players SET name = ?, total_online_minutes = ? WHERE uuid = ?")) {
            update.setString(1, name);
            update.setLong(2, minutes);
            update.setString(3, uuid.toString());
            update.executeUpdate();
        }
    }
}
