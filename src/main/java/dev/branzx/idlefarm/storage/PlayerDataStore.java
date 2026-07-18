package dev.branzx.idlefarm.storage;

import dev.branzx.idlefarm.IdleFarmPlugin;

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

public final class PlayerDataStore {

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();

    public PlayerDataStore(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public PlayerData loadOrCreateSync(UUID uuid, String name) {
        try (Connection connection = database.getConnection();
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
        try (Connection connection = database.getConnection();
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
        try (Connection connection = database.getConnection();
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
