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
 * Player identity and Coin access. Coin now lives in the shared
 * {@code wallet_accounts.coins} owned by BranzWallet, while identity and
 * playtime stay in {@code idle_players}. The two tables live in the same
 * database, so a coin write can still commit in the same transaction as the
 * gameplay row it pays for.
 *
 * <p>Coin is stored as a whole-number {@code long} but kept as a {@code double}
 * in {@link PlayerData} so the rest of the plugin is untouched; the rounding
 * happens only here, at the SQL boundary.
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

    public void saveSync(PlayerData data) {
        long revision = data.getRevision();
        UUID uuid = data.getUuid();
        String name = data.getName();
        double balance = data.getBalance();
        long minutes = data.getTotalOnlineMinutes();
        boolean committed = database.executeTransaction("save player " + uuid, connection -> {
            writeIdentity(connection, uuid, name, minutes);
            writeCoins(connection, uuid, name, balance);
        });
        if (committed) data.markPersisted(revision);
    }

    /**
     * Queues a snapshot save behind all preceding gameplay writes. Revision
     * checking prevents a later mutation from being marked clean by an older
     * snapshot completing asynchronously.
     */
    public void saveAsync(PlayerData data) {
        if (data == null || !data.isDirty()) return;
        long revision = data.getRevision();
        String name = data.getName();
        double balance = data.getBalance();
        long minutes = data.getTotalOnlineMinutes();
        UUID uuid = data.getUuid();
        database.submitWrite(() -> saveSnapshot(uuid, name, balance, minutes, data, revision));
    }

    public void saveAllDirtyAsync() {
        online.values().forEach(this::saveAsync);
    }

    private void saveSnapshot(UUID uuid, String name, double balance, long minutes,
                              PlayerData source, long revision) {
        try (Connection connection = database.getConnection()) {
            writeIdentity(connection, uuid, name, minutes);
            writeCoins(connection, uuid, name, balance);
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
    //
    // A player is logged into exactly one backend server at a time, so the
    // in-memory PlayerData is authoritative while they are online here. Every
    // other case — a player online on a sibling server, or offline entirely —
    // must go straight to the shared database, and must do so with a relative
    // UPDATE. Reading a balance, computing a new one and writing it back would
    // lose whichever server wrote last.

    /**
     * Balance for any player, online here or not. Returns 0 for an account
     * that does not exist yet, which is what Vault callers expect.
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

    /** Adds to any player's balance. Never fails on the amount. */
    public boolean deposit(UUID uuid, double amount) {
        PlayerData cached = online.get(uuid);
        if (cached != null) {
            cached.addBalance(amount);
            return true;
        }
        return database.executeTransaction("deposit " + uuid, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = coins + ? WHERE uuid = ?")) {
                update.setLong(1, Math.round(amount));
                update.setString(2, uuid.toString());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Wallet row is missing");
                }
            }
        });
    }

    /**
     * Removes from any player's balance, refusing to overdraw. For an offline
     * player the sufficiency check is part of the UPDATE, so two servers
     * spending the same Coins cannot both succeed.
     */
    public boolean withdraw(UUID uuid, double amount) {
        PlayerData cached = online.get(uuid);
        if (cached != null) {
            if (cached.getBalance() < amount) {
                return false;
            }
            cached.addBalance(-amount);
            return true;
        }
        long units = Math.round(amount);
        return database.executeTransaction("withdraw " + uuid, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = coins - ? "
                            + "WHERE uuid = ? AND coins >= ?")) {
                update.setLong(1, units);
                update.setString(2, uuid.toString());
                update.setLong(3, units);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Insufficient funds or missing wallet row");
                }
            }
        });
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

    /**
     * Writes the whole-Coin balance snapshot. The wallet row is upserted so a
     * snapshot for an account created on another backend still lands.
     */
    private void writeCoins(Connection connection, UUID uuid, String name, double balance)
            throws SQLException {
        ensureWalletRow(connection, uuid, name);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_accounts SET name = ?, coins = ? WHERE uuid = ?")) {
            update.setString(1, name);
            update.setLong(2, Math.round(balance));
            update.setString(3, uuid.toString());
            update.executeUpdate();
        }
    }
}
