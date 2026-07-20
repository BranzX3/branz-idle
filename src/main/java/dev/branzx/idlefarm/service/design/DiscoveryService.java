package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifetime resource discoveries and the account-wide rare-resource caps.
 * Caps apply to every acquisition path (passive production and event loot).
 */
public final class DiscoveryService {

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final TelemetryService telemetry;
    private final Map<String, Integer> capCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> discoveries = new ConcurrentHashMap<>();

    public DiscoveryService(IdleFarmPlugin plugin, Database database, TelemetryService telemetry) {
        this.plugin = plugin;
        this.database = database;
        this.telemetry = telemetry;
    }

    public void loadSync() {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, node_type, material, lifetime_count FROM idlefarm_discoveries");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    discoveries.put(discoveryKey(UUID.fromString(rs.getString("owner_uuid")),
                                    rs.getString("node_type"), rs.getString("material")),
                            rs.getLong("lifetime_count"));
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, material, period_key, amount FROM idlefarm_resource_caps");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    capCounts.put(capKey(UUID.fromString(rs.getString("owner_uuid")),
                            rs.getString("material"), rs.getString("period_key")), rs.getInt("amount"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load discovery state: " + e.getMessage());
        }
    }

    /**
     * Reserves one unit of a capped rare resource. Returns false when the
     * daily/weekly account cap is exhausted; the caller rerolls the drop.
     */
    public boolean allowResource(UUID owner, String material, int nodeLevel) {
        String normalized = material.toUpperCase(Locale.ROOT);
        int cap;
        String period;
        if (RareResources.DAILY.contains(normalized)) {
            period = GameClock.dayKey();
            cap = switch (normalized) {
                case "DIAMOND" -> nodeLevel >= 80 ? 16 : 8;
                case "EMERALD" -> 12;
                default -> 4;
            };
        } else if (RareResources.WEEKLY.contains(normalized)) {
            period = GameClock.weekKey();
            cap = ("ANCIENT_DEBRIS".equals(normalized) || "NETHERITE_SCRAP".equals(normalized)) ? 2 : 1;
        } else {
            return true;
        }
        String capMaterial = ("ANCIENT_DEBRIS".equals(normalized) || "NETHERITE_SCRAP".equals(normalized))
                ? "ANCIENT_MATERIAL" : normalized;
        String key = capKey(owner, capMaterial, period);
        int used = capCounts.getOrDefault(key, 0);
        if (used >= cap) {
            telemetry.record(owner, "RARE_CAP_HIT", "{\"material\":\"" + normalized + "\"}");
            return false;
        }
        capCounts.put(key, used + 1);
        persistCap(owner, capMaterial, period, used + 1);
        return true;
    }

    public Map<String, Long> discoveries(UUID owner, NodeType type) {
        Map<String, Long> result = new LinkedHashMap<>();
        String prefix = owner + "|" + type.name() + "|";
        discoveries.forEach((key, value) -> {
            if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), value);
        });
        return result;
    }

    /** Records production; returns true when the resource is newly discovered. */
    public boolean discover(UUID owner, NodeType type, String material, int amount) {
        if (amount <= 0) return false;
        String normalized = material.toUpperCase(Locale.ROOT);
        String key = discoveryKey(owner, type.name(), normalized);
        boolean fresh = !discoveries.containsKey(key);
        long count = discoveries.merge(key, (long) amount, Long::sum);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                int updated;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE idlefarm_discoveries SET lifetime_count = ? "
                                + "WHERE owner_uuid = ? AND node_type = ? AND material = ?")) {
                    update.setLong(1, count);
                    update.setString(2, owner.toString());
                    update.setString(3, type.name());
                    update.setString(4, normalized);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO idlefarm_discoveries "
                                    + "(owner_uuid, node_type, material, lifetime_count) VALUES (?, ?, ?, ?)")) {
                        insert.setString(1, owner.toString());
                        insert.setString(2, type.name());
                        insert.setString(3, normalized);
                        insert.setLong(4, count);
                        insert.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist discovery: " + e.getMessage());
            }
        });
        if (fresh) {
            // The unique_discoveries counter is fed by the facade so the
            // Chronicle can react to it; only telemetry stays here.
            telemetry.record(owner, "RESOURCE_DISCOVERED",
                    "{\"type\":\"" + type + "\",\"material\":\"" + normalized + "\"}");
        }
        return fresh;
    }

    private void persistCap(UUID owner, String material, String period, int amount) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_resource_caps (owner_uuid, material, period_key, amount) "
                                 + "VALUES (?, ?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setString(2, material);
                upsert.setString(3, period);
                upsert.setInt(4, amount);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist resource cap: " + e.getMessage());
            }
        });
    }

    private String discoveryKey(UUID owner, String type, String material) {
        return owner + "|" + type.toUpperCase(Locale.ROOT) + "|" + material.toUpperCase(Locale.ROOT);
    }

    private String capKey(UUID owner, String material, String period) {
        return owner + "|" + material.toUpperCase(Locale.ROOT) + "|" + period;
    }
}
