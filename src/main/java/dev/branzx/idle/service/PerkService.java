package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-time purchasable convenience perks (QoL money sinks).
 *
 * Launch perks:
 *  - auto_collect: node buffers flush to the Warehouse automatically
 *  - remote_collect: "Collect All" from the hub GUI, anywhere
 */
public final class PerkService {

    public static final String AUTO_COLLECT = "auto_collect";
    public static final String REMOTE_COLLECT = "remote_collect";
    /** Flag (not purchasable): owner has closed their territory to visitors. */
    public static final String NO_VISITS = "no_visits";

    private final IdlePlugin plugin;
    private final Database database;
    private final PlayerDataStore dataStore;
    private final Map<UUID, Set<String>> perks = new ConcurrentHashMap<>();

    public PerkService(IdlePlugin plugin, Database database, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.database = database;
        this.dataStore = dataStore;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, perk FROM idle_perks");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                perks.computeIfAbsent(UUID.fromString(rs.getString("owner_uuid")),
                        k -> ConcurrentHashMap.newKeySet()).add(rs.getString("perk"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load perks: " + e.getMessage());
        }
    }

    public boolean has(UUID owner, String perk) {
        Set<String> owned = perks.get(owner);
        return owned != null && owned.contains(perk);
    }

    public double cost(String perk) {
        return plugin.getConfig().getDouble("perks." + perk.toLowerCase(Locale.ROOT) + "-cost", 50000);
    }

    /** Free flag set/unset (visit toggle etc.) — no cost involved. */
    public void setFlag(UUID owner, String perk, boolean enabled) {
        if (enabled) {
            perks.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(perk);
        } else {
            Set<String> owned = perks.get(owner);
            if (owned != null) {
                owned.remove(perk);
            }
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement statement = enabled
                         ? connection.prepareStatement(
                                 "REPLACE INTO idle_perks (owner_uuid, perk) VALUES (?, ?)")
                         : connection.prepareStatement(
                                 "DELETE FROM idle_perks WHERE owner_uuid = ? AND perk = ?")) {
                statement.setString(1, owner.toString());
                statement.setString(2, perk);
                statement.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist perk flag: " + e.getMessage());
            }
        });
    }

    /** Buys the perk with Money; returns an error message or null on success. */
    public String buy(UUID owner, String perk) {
        if (has(owner, perk)) {
            return "You already own this perk.";
        }
        PlayerData data = dataStore.getOnline(owner);
        double cost = cost(perk);
        if (data == null || data.getBalance() < cost) {
            return "Not enough money (need " + cost + ").";
        }
        double balanceAfter = data.getBalance() - cost;
        boolean committed = database.executeTransaction("buy perk " + owner + " " + perk,
                connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                         "REPLACE INTO idle_perks (owner_uuid, perk) VALUES (?, ?)")) {
                insert.setString(1, owner.toString());
                insert.setString(2, perk);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = ? WHERE uuid = ?")) {
                update.setLong(1, Math.round(balanceAfter));
                update.setString(2, owner.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
            }
        });
        if (!committed) return "Purchase could not be settled; no Coins were charged.";
        data.addBalance(-cost);
        perks.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(perk);
        return null;
    }
}
