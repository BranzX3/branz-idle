package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timed multipliers bought with Money. Types: "money" (payout rate) and
 * "production" (node output rate). Buying while active extends the expiry.
 */
public final class BoosterService {

    public static final String MONEY = "money";
    public static final String PRODUCTION = "production";

    private record Active(double multiplier, long expiresAt) {
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final PlayerDataStore dataStore;
    // owner -> type -> active booster
    private final Map<UUID, Map<String, Active>> active = new ConcurrentHashMap<>();

    public BoosterService(IdleFarmPlugin plugin, Database database, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.database = database;
        this.dataStore = dataStore;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, booster_type, multiplier, expires_at FROM idlefarm_boosters");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                active.computeIfAbsent(UUID.fromString(rs.getString("owner_uuid")),
                                k -> new ConcurrentHashMap<>())
                        .put(rs.getString("booster_type"),
                                new Active(rs.getDouble("multiplier"),
                                        rs.getTimestamp("expires_at").getTime()));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load boosters: " + e.getMessage());
        }
    }

    /** Effective multiplier for the type; 1.0 when none active. */
    public double multiplier(UUID owner, String type) {
        Map<String, Active> byType = active.get(owner);
        if (byType == null) {
            return 1.0;
        }
        Active booster = byType.get(type);
        if (booster == null || booster.expiresAt() <= System.currentTimeMillis()) {
            return 1.0;
        }
        return booster.multiplier();
    }

    /** Remaining millis for display; 0 when inactive. */
    public long remainingMillis(UUID owner, String type) {
        Map<String, Active> byType = active.get(owner);
        if (byType == null) {
            return 0;
        }
        Active booster = byType.get(type);
        return booster == null ? 0 : Math.max(0, booster.expiresAt() - System.currentTimeMillis());
    }

    public double cost(String type) {
        return plugin.getConfig().getDouble("boosters." + type + ".cost", 2000);
    }

    public double boostMultiplier(String type) {
        return plugin.getConfig().getDouble("boosters." + type + ".multiplier", 2.0);
    }

    public long durationMinutes(String type) {
        return plugin.getConfig().getLong("boosters." + type + ".duration-minutes", 60);
    }

    /** Buys/extends a booster; returns an error message or null on success. */
    public String buy(UUID owner, String type) {
        PlayerData data = dataStore.getOnline(owner);
        double cost = cost(type);
        if (data == null || data.getBalance() < cost) {
            return "Not enough money (need " + cost + ").";
        }
        data.addBalance(-cost);

        long now = System.currentTimeMillis();
        long extension = durationMinutes(type) * 60_000L;
        Map<String, Active> byType = active.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        Active current = byType.get(type);
        long newExpiry = (current != null && current.expiresAt() > now ? current.expiresAt() : now) + extension;
        Active booster = new Active(boostMultiplier(type), newExpiry);
        byType.put(type, booster);

        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_boosters (owner_uuid, booster_type, multiplier, expires_at) "
                                 + "VALUES (?, ?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setString(2, type);
                upsert.setDouble(3, booster.multiplier());
                upsert.setTimestamp(4, new Timestamp(booster.expiresAt()));
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist booster: " + e.getMessage());
            }
        });
        return null;
    }
}
