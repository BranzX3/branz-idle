package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.node.NodeRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual per-player central storage. Node collect-all routes here rather
 * than to inventory; withdraw pulls back to inventory. Capacity (total item
 * count) is expandable with Money — a sink. All maps cached in memory;
 * writes go through the ordered async queue.
 */
public final class WarehouseService {

    private final IdleFarmPlugin plugin;
    private final Database database;
    // owner -> (MATERIAL -> count), insertion-ordered for stable paging
    private final Map<UUID, Map<String, Integer>> contents = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> capacities = new ConcurrentHashMap<>();

    public WarehouseService(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, capacity, content_json FROM idlefarm_warehouse");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                capacities.put(owner, rs.getInt("capacity"));
                contents.put(owner, deserialize(rs.getString("content_json")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load warehouses: " + e.getMessage());
        }
    }

    public int getCapacity(UUID owner) {
        return capacities.computeIfAbsent(owner,
                k -> plugin.getConfig().getInt("warehouse.base-capacity", 2000));
    }

    public Map<String, Integer> getContents(UUID owner) {
        return contents.computeIfAbsent(owner, k -> new LinkedHashMap<>());
    }

    public int total(UUID owner) {
        return getContents(owner).values().stream().mapToInt(Integer::intValue).sum();
    }

    public int freeSpace(UUID owner) {
        return Math.max(0, getCapacity(owner) - total(owner));
    }

    /**
     * Deposits up to {@code amount}, capped by free space. Returns the number
     * actually stored (caller handles the remainder/overflow).
     */
    public int deposit(UUID owner, String material, int amount) {
        int stored = Math.min(amount, freeSpace(owner));
        if (stored > 0) {
            getContents(owner).merge(material.toUpperCase(java.util.Locale.ROOT), stored, Integer::sum);
            persist(owner);
        }
        return stored;
    }

    /**
     * Atomically deposits an entire loot bundle into the in-memory warehouse.
     * Returns false without changing anything when the bundle does not fit.
     */
    public boolean depositAll(UUID owner, Map<String, Integer> items) {
        long requested = items.values().stream()
                .filter(amount -> amount != null && amount > 0)
                .mapToLong(Integer::longValue)
                .sum();
        if (requested > freeSpace(owner)) {
            return false;
        }
        Map<String, Integer> warehouse = getContents(owner);
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            int amount = entry.getValue() == null ? 0 : entry.getValue();
            if (amount > 0) {
                warehouse.merge(entry.getKey().toUpperCase(java.util.Locale.ROOT), amount, Integer::sum);
            }
        }
        if (requested > 0) {
            persist(owner);
        }
        return true;
    }

    /**
     * Atomically moves as much of a node buffer as fits. The cache changes on
     * the main thread and the two durable rows are committed in one ordered DB
     * transaction, preventing restart-time loss or duplication.
     */
    public int collectNode(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        Map<String, Integer> warehouse = getContents(owner);
        int moved = 0;
        synchronized (node) {
            int space = freeSpace(owner);
            for (Map.Entry<String, Integer> entry : List.copyOf(node.getStorage().entrySet())) {
                if (space <= 0) break;
                int amount = Math.min(space, entry.getValue());
                if (amount <= 0) continue;
                String key = entry.getKey().toUpperCase(java.util.Locale.ROOT);
                warehouse.merge(key, amount, Integer::sum);
                moved += amount;
                space -= amount;
                if (amount == entry.getValue()) node.getStorage().remove(entry.getKey());
                else node.getStorage().put(entry.getKey(), entry.getValue() - amount);
            }
            if (moved > 0) node.setState("ACTIVE");
        }
        if (moved <= 0) return 0;
        String warehouseJson = serialize(warehouse);
        String nodeJson = node.serializeStorage();
        int capacity = getCapacity(owner);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement upsert = connection.prepareStatement(
                        "REPLACE INTO idlefarm_warehouse (owner_uuid, capacity, content_json) VALUES (?, ?, ?)")) {
                    upsert.setString(1, owner.toString());
                    upsert.setInt(2, capacity);
                    upsert.setString(3, warehouseJson);
                    upsert.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE idlefarm_nodes SET storage_json = ?, state = ? WHERE id = ?")) {
                    update.setString(1, nodeJson);
                    update.setString(2, node.getState());
                    update.setLong(3, node.getId());
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException e) {
                plugin.getLogger().severe("Atomic node collection failed: " + e.getMessage());
            }
        });
        return moved;
    }

    /** Removes up to {@code amount}; returns the number actually removed. */
    public int withdraw(UUID owner, String material, int amount) {
        Map<String, Integer> map = getContents(owner);
        String key = material.toUpperCase(java.util.Locale.ROOT);
        int have = map.getOrDefault(key, 0);
        int removed = Math.min(have, amount);
        if (removed > 0) {
            if (removed == have) {
                map.remove(key);
            } else {
                map.put(key, have - removed);
            }
            persist(owner);
        }
        return removed;
    }

    public boolean expandCapacity(UUID owner, PlayerBalance balance) {
        int step = plugin.getConfig().getInt("warehouse.expand-step", 1000);
        double cost = plugin.getConfig().getDouble("warehouse.expand-cost", 5000);
        if (!balance.trySpend(cost)) {
            return false;
        }
        capacities.put(owner, getCapacity(owner) + step);
        persist(owner);
        return true;
    }

    /** Minimal spend hook so this service needn't depend on PlayerData directly. */
    public interface PlayerBalance {
        boolean trySpend(double amount);
    }

    private void persist(UUID owner) {
        int capacity = getCapacity(owner);
        String json = serialize(getContents(owner));
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_warehouse (owner_uuid, capacity, content_json) VALUES (?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setInt(2, capacity);
                upsert.setString(3, json);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist warehouse for " + owner + ": " + e.getMessage());
            }
        });
    }

    private String serialize(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    private Map<String, Integer> deserialize(String value) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (value != null && !value.isBlank()) {
            for (String entry : value.split(";")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    map.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
                }
            }
        }
        return map;
    }
}
