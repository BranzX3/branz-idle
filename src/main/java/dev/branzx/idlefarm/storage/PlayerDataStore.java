package dev.branzx.idlefarm.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataStore {

    private final IdleFarmPlugin plugin;
    private HikariDataSource dataSource;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();

    public PlayerDataStore(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("mysql");
        HikariConfig hikariConfig = new HikariConfig();
        String host = cfg.getString("host", "localhost");
        int port = cfg.getInt("port", 3306);
        String database = cfg.getString("database", "idlefarm");
        boolean useSSL = cfg.getBoolean("useSSL", false);

        hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=utf8");
        hikariConfig.setUsername(cfg.getString("username", "root"));
        hikariConfig.setPassword(cfg.getString("password", ""));
        hikariConfig.setMaximumPoolSize(cfg.getInt("pool-size", 10));
        hikariConfig.setPoolName("IdleFarm-Pool");

        this.dataSource = new HikariDataSource(hikariConfig);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS idlefarm_players (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        name VARCHAR(16) NOT NULL,
                        balance DOUBLE NOT NULL DEFAULT 0,
                        total_online_minutes BIGINT NOT NULL DEFAULT 0
                    )
                    """);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize MySQL schema: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public PlayerData loadOrCreateSync(UUID uuid, String name) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT balance, total_online_minutes FROM idlefarm_players WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    PlayerData data = new PlayerData(uuid, name, rs.getDouble("balance"), rs.getLong("total_online_minutes"));
                    online.put(uuid, data);
                    return data;
                }
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idlefarm_players (uuid, name, balance, total_online_minutes) VALUES (?, ?, 0, 0)")) {
                insert.setString(1, uuid.toString());
                insert.setString(2, name);
                insert.executeUpdate();
            }

            PlayerData data = new PlayerData(uuid, name, 0, 0);
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE idlefarm_players SET name = ?, balance = ?, total_online_minutes = ? WHERE uuid = ?")) {
            update.setString(1, data.getName());
            update.setDouble(2, data.getBalance());
            update.setLong(3, data.getTotalOnlineMinutes());
            update.setString(4, data.getUuid().toString());
            update.executeUpdate();
            data.clearDirty();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save player data for " + data.getUuid() + ": " + e.getMessage());
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

    public List<PlayerData> loadTopSync(int limit, int minMinutes) {
        List<PlayerData> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT uuid, name, balance, total_online_minutes FROM idlefarm_players "
                             + "WHERE total_online_minutes >= ? ORDER BY balance DESC LIMIT ?")) {
            select.setInt(1, minMinutes);
            select.setInt(2, limit);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    result.add(new PlayerData(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("name"),
                            rs.getDouble("balance"),
                            rs.getLong("total_online_minutes")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load top players: " + e.getMessage());
        }
        return result;
    }
}
