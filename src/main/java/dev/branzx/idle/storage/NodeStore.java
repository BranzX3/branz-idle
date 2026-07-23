package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.node.TrustLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All claimed nodes are cached in memory for the lifetime of the server;
 * chunk-lookup must be O(1) because the protection listener runs on the
 * hot path of every block event.
 */
public final class NodeStore {

    private final IdlePlugin plugin;
    private final Database database;

    private final Map<ChunkKey, NodeRecord> byChunk = new ConcurrentHashMap<>();
    private final Map<UUID, List<NodeRecord>> byOwner = new ConcurrentHashMap<>();
    // trust: owner -> (trusted -> level)
    private final Map<UUID, Map<UUID, TrustLevel>> trust = new ConcurrentHashMap<>();
    // Client-generated ids so inserts never wait on the DB round-trip.
    private final AtomicLong nextNodeId = new AtomicLong(1);

    public NodeStore(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, owner_uuid, world, chunk_x, chunk_z, node_type, tier, state, origin_y, "
                            + "last_tick_at, storage_json, bulk_storage_json, bulk_last_tick_at, "
                            + "exploration_level, exploration_exp, skin_id, rotation, "
                            + "complex_anchor, upgrade_ends_at FROM idle_nodes");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    NodeRecord record = new NodeRecord(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("owner_uuid")),
                            new ChunkKey(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z")),
                            NodeType.fromString(rs.getString("node_type")),
                            rs.getInt("tier"),
                            rs.getString("state"),
                            rs.getInt("origin_y"),
                            rs.getTimestamp("last_tick_at") != null
                                    ? rs.getTimestamp("last_tick_at").getTime()
                                    : System.currentTimeMillis(),
                            rs.getString("storage_json"));
                    record.setExplorationLevel(Math.max(1, rs.getInt("exploration_level")));
                    record.setExplorationExp(rs.getLong("exploration_exp"));
                    record.setUpgradeEndsAt(rs.getLong("upgrade_ends_at"));
                    record.setSkinId(rs.getString("skin_id"));
                    record.setRotation(rs.getInt("rotation"));
                    record.setComplexAnchor(rs.getLong("complex_anchor"));
                    record.loadBulkStorage(rs.getString("bulk_storage_json"));
                    long bulkAnchor = rs.getLong("bulk_last_tick_at");
                    // 0 = row predates the bulk lane; start from the discovery
                    // anchor so migration never backfills a production windfall.
                    record.setBulkLastTickAt(bulkAnchor > 0 ? bulkAnchor : record.getLastTickAt());
                    index(record);
                    nextNodeId.accumulateAndGet(record.getId() + 1, Math::max);
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, base_cap, bonus_cap FROM idle_node_cap");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    capCache.put(UUID.fromString(rs.getString("owner_uuid")),
                            rs.getInt("base_cap") + rs.getInt("bonus_cap"));
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, trusted_uuid, level FROM idle_trust");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                    UUID trusted = UUID.fromString(rs.getString("trusted_uuid"));
                    TrustLevel level = TrustLevel.fromString(rs.getString("level"));
                    if (level != null) {
                        trust.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(trusted, level);
                    }
                }
            }
            plugin.getLogger().info("Loaded " + byChunk.size() + " nodes and trust entries for "
                    + trust.size() + " owners.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load nodes: " + e.getMessage());
        }
    }

    private void index(NodeRecord record) {
        byChunk.put(record.getChunk(), record);
        byOwner.computeIfAbsent(record.getOwnerUuid(), k -> new ArrayList<>()).add(record);
    }

    private void unindex(NodeRecord record) {
        byChunk.remove(record.getChunk());
        List<NodeRecord> owned = byOwner.get(record.getOwnerUuid());
        if (owned != null) {
            owned.remove(record);
        }
    }

    public NodeRecord getByChunk(ChunkKey key) {
        return byChunk.get(key);
    }

    /** Linear scan; use for rare authorization checks, not hot paths. */
    public NodeRecord getById(long id) {
        for (NodeRecord record : byChunk.values()) {
            if (record.getId() == id) {
                return record;
            }
        }
        return null;
    }

    public List<NodeRecord> getByOwner(UUID owner) {
        return List.copyOf(byOwner.getOrDefault(owner, List.of()));
    }

    public List<NodeRecord> getAll() {
        return List.copyOf(byChunk.values());
    }

    /** Persist production-mutable fields (state, buffer, tick anchor, tier, type). */
    public void updateProduction(NodeRecord record) {
        String storageJson = record.serializeStorage();
        String bulkStorageJson = record.serializeBulkStorage();
        String state = record.getState();
        long lastTick = record.getLastTickAt();
        long bulkLastTick = record.getBulkLastTickAt();
        int tier = record.getTier();
        int explorationLevel = record.getExplorationLevel();
        long explorationExp = record.getExplorationExp();
        String nodeType = record.getType().name();
        long upgradeEndsAt = record.getUpgradeEndsAt();
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE idle_nodes SET state = ?, storage_json = ?, last_tick_at = ?, tier = ?, "
                                 + "exploration_level = ?, exploration_exp = ?, node_type = ?, "
                                 + "upgrade_ends_at = ?, bulk_storage_json = ?, bulk_last_tick_at = ? "
                                 + "WHERE id = ?")) {
                update.setString(1, state);
                update.setString(2, storageJson);
                update.setTimestamp(3, new java.sql.Timestamp(lastTick));
                update.setInt(4, tier);
                update.setInt(5, explorationLevel);
                update.setLong(6, explorationExp);
                update.setString(7, nodeType);
                update.setLong(8, upgradeEndsAt);
                update.setString(9, bulkStorageJson);
                update.setLong(10, bulkLastTick);
                update.setLong(11, record.getId());
                update.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update node " + record.getId() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Persists appearance-only fields (skin, rotation). Kept off the
     * production update so a rotation never rewrites buffer or tick anchors.
     */
    public void updateAppearance(NodeRecord record) {
        String skinId = record.getSkinId();
        int rotation = record.getRotation();
        long complexAnchor = record.getComplexAnchor();
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE idle_nodes SET skin_id = ?, rotation = ?, complex_anchor = ?, "
                                 + "origin_y = ? WHERE id = ?")) {
                update.setString(1, skinId);
                update.setInt(2, rotation);
                update.setLong(3, complexAnchor);
                // Residential members adopt the anchor's ground level when
                // they join, so origin_y travels with appearance.
                update.setInt(4, record.getOriginY());
                update.setLong(5, record.getId());
                update.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update appearance for node "
                        + record.getId() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Commits appearance changes for one or more nodes and their Coin cost as
     * one durable unit. Rotation of a Complex and initial membership must not
     * charge first and leave the appearance writes queued separately.
     */
    public boolean updateAppearancesWithCost(List<NodeRecord> records, PlayerData player,
                                             double cost) {
        record Appearance(long id, String skinId, int rotation, long complexAnchor, int originY) {
        }
        List<Appearance> appearances = records.stream()
                .map(record -> new Appearance(record.getId(), record.getSkinId(),
                        record.getRotation(), record.getComplexAnchor(), record.getOriginY()))
                .toList();
        double balanceAfter = player.getBalance() - cost;
        boolean committed = database.executeTransaction("paid appearance update", connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_nodes SET skin_id = ?, rotation = ?, complex_anchor = ?, "
                            + "origin_y = ? WHERE id = ?")) {
                for (Appearance appearance : appearances) {
                    update.setString(1, appearance.skinId());
                    update.setInt(2, appearance.rotation());
                    update.setLong(3, appearance.complexAnchor());
                    update.setInt(4, appearance.originY());
                    update.setLong(5, appearance.id());
                    update.addBatch();
                }
                int[] changed = update.executeBatch();
                for (int count : changed) {
                    if (count == 0) throw new SQLException("Node row is missing");
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = ? WHERE uuid = ?")) {
                update.setLong(1, Math.round(balanceAfter));
                update.setString(2, player.getUuid().toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
            }
        });
        if (committed) player.addBalance(-cost);
        return committed;
    }

    /** Commits a production-state mutation and its Coin cost together. */
    public boolean updateProductionWithCost(NodeRecord record, PlayerData player, double cost) {
        String storageJson = record.serializeStorage();
        String bulkStorageJson = record.serializeBulkStorage();
        String state = record.getState();
        long lastTick = record.getLastTickAt();
        long bulkLastTick = record.getBulkLastTickAt();
        int tier = record.getTier();
        int explorationLevel = record.getExplorationLevel();
        long explorationExp = record.getExplorationExp();
        String nodeType = record.getType().name();
        long upgradeEndsAt = record.getUpgradeEndsAt();
        double balanceAfter = player.getBalance() - cost;
        boolean committed = database.executeTransaction("paid node update " + record.getId(),
                connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_nodes SET state = ?, storage_json = ?, last_tick_at = ?, "
                            + "tier = ?, exploration_level = ?, exploration_exp = ?, node_type = ?, "
                            + "upgrade_ends_at = ?, bulk_storage_json = ?, bulk_last_tick_at = ? "
                            + "WHERE id = ?")) {
                update.setString(1, state);
                update.setString(2, storageJson);
                update.setTimestamp(3, new java.sql.Timestamp(lastTick));
                update.setInt(4, tier);
                update.setInt(5, explorationLevel);
                update.setLong(6, explorationExp);
                update.setString(7, nodeType);
                update.setLong(8, upgradeEndsAt);
                update.setString(9, bulkStorageJson);
                update.setLong(10, bulkLastTick);
                update.setLong(11, record.getId());
                if (update.executeUpdate() != 1) throw new SQLException("Node row is missing");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = ? WHERE uuid = ?")) {
                update.setLong(1, Math.round(balanceAfter));
                update.setString(2, player.getUuid().toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
            }
        });
        if (committed) player.addBalance(-cost);
        return committed;
    }

    /**
     * Creates a claim and settles its Coin cost in one ordered transaction.
     * The node is indexed only after the durable commit succeeds.
     */
    public NodeRecord insert(UUID owner, ChunkKey chunk, NodeType type, int originY,
                             PlayerData player, double cost) {
        NodeRecord record = new NodeRecord(nextNodeId.getAndIncrement(), owner, chunk, type, 1,
                NodeRecord.STATE_IDLE,
                originY, System.currentTimeMillis(), null);
        record.setExplorationLevel(1);
        double balanceAfter = player.getBalance() - cost;
        boolean committed = database.executeTransaction("claim node " + record.getId(), connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idle_nodes (id, owner_uuid, world, chunk_x, chunk_z, node_type, tier, state, origin_y, exploration_level) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, 1, 'IDLE', ?, 1)")) {
                insert.setLong(1, record.getId());
                insert.setString(2, owner.toString());
                insert.setString(3, chunk.world());
                insert.setInt(4, chunk.x());
                insert.setInt(5, chunk.z());
                insert.setString(6, type.name());
                insert.setInt(7, originY);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = ? WHERE uuid = ?")) {
                update.setLong(1, Math.round(balanceAfter));
                update.setString(2, owner.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
            }
        });
        if (!committed) return null;
        index(record);
        player.addBalance(-cost);
        return record;
    }

    /**
     * Deletes a node and settles its unclaim refund in one blocking
     * transaction; the node stays claimed when the commit fails.
     */
    public boolean deleteWithRefund(NodeRecord record, PlayerData player, double refund) {
        double balanceAfter = player.getBalance() + refund;
        boolean committed = database.executeTransaction("unclaim node " + record.getId(),
                connection -> {
            try (PreparedStatement research = connection.prepareStatement(
                    "DELETE FROM idle_node_research WHERE node_id = ?")) {
                research.setLong(1, record.getId());
                research.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM idle_nodes WHERE id = ?")) {
                delete.setLong(1, record.getId());
                delete.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE wallet_accounts SET coins = ? WHERE uuid = ?")) {
                update.setLong(1, Math.round(balanceAfter));
                update.setString(2, player.getUuid().toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
            }
        });
        if (!committed) return false;
        unindex(record);
        player.addBalance(refund);
        return true;
    }

    /** In-memory removal is immediate; durable delete is queued. */
    public void delete(NodeRecord record) {
        unindex(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                try (PreparedStatement research = connection.prepareStatement(
                        "DELETE FROM idle_node_research WHERE node_id = ?")) {
                    research.setLong(1, record.getId());
                    research.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM idle_nodes WHERE id = ?")) {
                    delete.setLong(1, record.getId());
                    delete.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete node " + record.getId() + ": " + e.getMessage());
            }
        });
    }

    // ---- node cap (cached at startup; caps change rarely) ----

    private final Map<UUID, Integer> capCache = new ConcurrentHashMap<>();

    public int getCap(UUID owner, int defaultBaseCap) {
        return capCache.getOrDefault(owner, defaultBaseCap);
    }

    public void setCap(UUID owner, int baseCap, int bonusCap) {
        capCache.put(owner, baseCap + bonusCap);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idle_node_cap (owner_uuid, base_cap, bonus_cap) VALUES (?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setInt(2, baseCap);
                upsert.setInt(3, bonusCap);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist node cap for " + owner + ": " + e.getMessage());
            }
        });
    }

    // ---- trust ----

    public TrustLevel getTrust(UUID owner, UUID trusted) {
        Map<UUID, TrustLevel> map = trust.get(owner);
        return map == null ? null : map.get(trusted);
    }

    public Map<UUID, TrustLevel> getTrustedOf(UUID owner) {
        return Map.copyOf(trust.getOrDefault(owner, Map.of()));
    }

    public void setTrust(UUID owner, UUID trusted, TrustLevel level) {
        trust.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(trusted, level);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idle_trust (owner_uuid, trusted_uuid, level) VALUES (?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setString(2, trusted.toString());
                upsert.setString(3, level.name());
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist trust: " + e.getMessage());
            }
        });
    }

    public void removeTrust(UUID owner, UUID trusted) {
        Map<UUID, TrustLevel> map = trust.get(owner);
        if (map != null) {
            map.remove(trusted);
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idle_trust WHERE owner_uuid = ? AND trusted_uuid = ?")) {
                delete.setString(1, owner.toString());
                delete.setString(2, trusted.toString());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to remove trust: " + e.getMessage());
            }
        });
    }
}
