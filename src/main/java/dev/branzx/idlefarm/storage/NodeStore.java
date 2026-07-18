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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    public NodeStore(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, owner_uuid, world, chunk_x, chunk_z, node_type, tier, state FROM idlefarm_nodes");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    NodeRecord record = new NodeRecord(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("owner_uuid")),
                            new ChunkKey(rs.getString("world"), rs.getInt("chunk_x"), rs.getInt("chunk_z")),
                            NodeType.fromString(rs.getString("node_type")),
                            rs.getInt("tier"),
                            rs.getString("state"));
                    index(record);
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

    public NodeRecord insertSync(UUID owner, ChunkKey chunk, NodeType type) {
        try (Connection connection = database.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO idlefarm_nodes (owner_uuid, world, chunk_x, chunk_z, node_type, tier, state) "
                             + "VALUES (?, ?, ?, ?, ?, 1, 'ACTIVE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, owner.toString());
            insert.setString(2, chunk.world());
            insert.setInt(3, chunk.x());
            insert.setInt(4, chunk.z());
            insert.setString(5, type.name());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    NodeRecord record = new NodeRecord(keys.getLong(1), owner, chunk, type, 1, "ACTIVE");
                    index(record);
                    return record;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to insert node at " + chunk + ": " + e.getMessage());
        }
        return null;
    }

    public boolean deleteSync(NodeRecord record) {
        try (Connection connection = database.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM idlefarm_nodes WHERE id = ?")) {
            delete.setLong(1, record.getId());
            delete.executeUpdate();
            unindex(record);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete node " + record.getId() + ": " + e.getMessage());
            return false;
        }
    }

    // ---- node cap ----

    public int loadCapSync(UUID owner, int defaultBaseCap) {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT base_cap, bonus_cap FROM idlefarm_node_cap WHERE owner_uuid = ?")) {
            select.setString(1, owner.toString());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("base_cap") + rs.getInt("bonus_cap");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load node cap for " + owner + ": " + e.getMessage());
        }
        return defaultBaseCap;
    }

    // ---- trust ----

    public TrustLevel getTrust(UUID owner, UUID trusted) {
        Map<UUID, TrustLevel> map = trust.get(owner);
        return map == null ? null : map.get(trusted);
    }

    public Map<UUID, TrustLevel> getTrustedOf(UUID owner) {
        return Map.copyOf(trust.getOrDefault(owner, Map.of()));
    }

    public void setTrustSync(UUID owner, UUID trusted, TrustLevel level) {
        try (Connection connection = database.getConnection();
             PreparedStatement upsert = connection.prepareStatement(
                     "INSERT INTO idlefarm_trust (owner_uuid, trusted_uuid, level) VALUES (?, ?, ?) "
                             + "ON DUPLICATE KEY UPDATE level = VALUES(level)")) {
            upsert.setString(1, owner.toString());
            upsert.setString(2, trusted.toString());
            upsert.setString(3, level.name());
            upsert.executeUpdate();
            trust.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(trusted, level);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to set trust: " + e.getMessage());
        }
    }

    public void removeTrustSync(UUID owner, UUID trusted) {
        try (Connection connection = database.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM idlefarm_trust WHERE owner_uuid = ? AND trusted_uuid = ?")) {
            delete.setString(1, owner.toString());
            delete.setString(2, trusted.toString());
            delete.executeUpdate();
            Map<UUID, TrustLevel> map = trust.get(owner);
            if (map != null) {
                map.remove(trusted);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to remove trust: " + e.getMessage());
        }
    }
}
