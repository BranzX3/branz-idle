package dev.branzx.idlefarm.storage;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.node.TrustLevel;

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

    private final IdleFarmPlugin plugin;
    private final Database database;

    private final Map<ChunkKey, NodeRecord> byChunk = new ConcurrentHashMap<>();
    private final Map<UUID, List<NodeRecord>> byOwner = new ConcurrentHashMap<>();
    // trust: owner -> (trusted -> level)
    private final Map<UUID, Map<UUID, TrustLevel>> trust = new ConcurrentHashMap<>();
    // Client-generated ids so inserts never wait on the DB round-trip.
    private final AtomicLong nextNodeId = new AtomicLong(1);

    public NodeStore(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, owner_uuid, world, chunk_x, chunk_z, node_type, tier, state, origin_y, "
                            + "last_tick_at, storage_json, exploration_level, exploration_exp FROM idlefarm_nodes");
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
                    record.setExplorationLevel(rs.getInt("exploration_level"));
                    record.setExplorationExp(rs.getLong("exploration_exp"));
                    index(record);
                    nextNodeId.accumulateAndGet(record.getId() + 1, Math::max);
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, base_cap, bonus_cap FROM idlefarm_node_cap");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    capCache.put(UUID.fromString(rs.getString("owner_uuid")),
                            rs.getInt("base_cap") + rs.getInt("bonus_cap"));
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, trusted_uuid, level FROM idlefarm_trust");
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

    public List<NodeRecord> getByOwner(UUID owner) {
        return List.copyOf(byOwner.getOrDefault(owner, List.of()));
    }

    public List<NodeRecord> getAll() {
        return List.copyOf(byChunk.values());
    }

    /** Persist production-mutable fields (state, buffer, tick anchor, tier). */
    public void updateProduction(NodeRecord record) {
        String storageJson = record.serializeStorage();
        String state = record.getState();
        long lastTick = record.getLastTickAt();
        int tier = record.getTier();
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE idlefarm_nodes SET state = ?, storage_json = ?, last_tick_at = ?, tier = ?, "
                                 + "exploration_level = ?, exploration_exp = ? WHERE id = ?")) {
                update.setString(1, state);
                update.setString(2, storageJson);
                update.setTimestamp(3, new java.sql.Timestamp(lastTick));
                update.setInt(4, tier);
                update.setInt(5, record.getExplorationLevel());
                update.setLong(6, record.getExplorationExp());
                update.setLong(7, record.getId());
                update.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update node " + record.getId() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Applies to the in-memory index immediately (main thread, authoritative)
     * and queues the durable insert. Never blocks on the DB.
     */
    public NodeRecord insert(UUID owner, ChunkKey chunk, NodeType type, int originY) {
        NodeRecord record = new NodeRecord(nextNodeId.getAndIncrement(), owner, chunk, type, 1, "ACTIVE",
                originY, System.currentTimeMillis(), null);
        index(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idlefarm_nodes (id, owner_uuid, world, chunk_x, chunk_z, node_type, tier, state, origin_y) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, 1, 'ACTIVE', ?)")) {
                insert.setLong(1, record.getId());
                insert.setString(2, owner.toString());
                insert.setString(3, chunk.world());
                insert.setInt(4, chunk.x());
                insert.setInt(5, chunk.z());
                insert.setString(6, type.name());
                insert.setInt(7, originY);
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist node at " + chunk + ": " + e.getMessage());
            }
        });
        return record;
    }

    /** In-memory removal is immediate; durable delete is queued. */
    public void delete(NodeRecord record) {
        unindex(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idlefarm_nodes WHERE id = ?")) {
                delete.setLong(1, record.getId());
                delete.executeUpdate();
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
                         "REPLACE INTO idlefarm_node_cap (owner_uuid, base_cap, bonus_cap) VALUES (?, ?, ?)")) {
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
                         "REPLACE INTO idlefarm_trust (owner_uuid, trusted_uuid, level) VALUES (?, ?, ?)")) {
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
                         "DELETE FROM idlefarm_trust WHERE owner_uuid = ? AND trusted_uuid = ?")) {
                delete.setString(1, owner.toString());
                delete.setString(2, trusted.toString());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to remove trust: " + e.getMessage());
            }
        });
    }
}
