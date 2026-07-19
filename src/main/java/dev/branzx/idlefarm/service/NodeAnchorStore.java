package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-placed spawn/work anchor overrides, keyed by worker UUID so they
 * follow the worker regardless of slot shuffling (Stage B). World
 * coordinates are the source of truth; the placed head was only a gesture.
 * Cleared on eject (per worker) and on tier upgrade / unclaim (per node).
 * Cached in memory; writes go through the ordered async queue.
 */
public final class NodeAnchorStore {

    public record SlotAnchor(int spawnX, int spawnY, int spawnZ, int workX, int workY, int workZ) {
        public Location spawn(World world) {
            return new Location(world, spawnX + 0.5, spawnY, spawnZ + 0.5);
        }

        public Location work(World world) {
            return new Location(world, workX + 0.5, workY, workZ + 0.5);
        }
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final Map<UUID, SlotAnchor> byWorker = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nodeOf = new ConcurrentHashMap<>();

    public NodeAnchorStore(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT worker_uuid, node_id, spawn_x, spawn_y, spawn_z, work_x, work_y, work_z "
                             + "FROM idlefarm_worker_anchors");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                UUID worker = UUID.fromString(rs.getString("worker_uuid"));
                byWorker.put(worker, new SlotAnchor(
                        rs.getInt("spawn_x"), rs.getInt("spawn_y"), rs.getInt("spawn_z"),
                        rs.getInt("work_x"), rs.getInt("work_y"), rs.getInt("work_z")));
                nodeOf.put(worker, rs.getLong("node_id"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load worker anchors: " + e.getMessage());
        }
    }

    public SlotAnchor get(UUID workerUuid) {
        return byWorker.get(workerUuid);
    }

    public void set(UUID workerUuid, long nodeId, SlotAnchor anchor) {
        byWorker.put(workerUuid, anchor);
        nodeOf.put(workerUuid, nodeId);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_worker_anchors (worker_uuid, node_id, spawn_x, spawn_y, "
                                 + "spawn_z, work_x, work_y, work_z) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                upsert.setString(1, workerUuid.toString());
                upsert.setLong(2, nodeId);
                upsert.setInt(3, anchor.spawnX());
                upsert.setInt(4, anchor.spawnY());
                upsert.setInt(5, anchor.spawnZ());
                upsert.setInt(6, anchor.workX());
                upsert.setInt(7, anchor.workY());
                upsert.setInt(8, anchor.workZ());
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist worker anchor: " + e.getMessage());
            }
        });
    }

    /** Clear one worker's override (on eject). */
    public void clearWorker(UUID workerUuid) {
        byWorker.remove(workerUuid);
        nodeOf.remove(workerUuid);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idlefarm_worker_anchors WHERE worker_uuid = ?")) {
                delete.setString(1, workerUuid.toString());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete worker anchor: " + e.getMessage());
            }
        });
    }

    /** Clear all overrides for a node (tier upgrade / unclaim). */
    public void clearNode(long nodeId) {
        nodeOf.entrySet().removeIf(e -> {
            if (e.getValue() == nodeId) {
                byWorker.remove(e.getKey());
                return true;
            }
            return false;
        });
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idlefarm_worker_anchors WHERE node_id = ?")) {
                delete.setLong(1, nodeId);
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to clear node anchors: " + e.getMessage());
            }
        });
    }
}
