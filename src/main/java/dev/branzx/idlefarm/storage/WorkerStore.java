package dev.branzx.idlefarm.storage;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.Trait;
import dev.branzx.idlefarm.worker.WorkerRecord;
import dev.branzx.idlefarm.worker.WorkerStats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every minted worker has a DB row; the row is the authority for stats/state
 * while the item is only a carrying token holding the worker_uuid. All rows
 * are cached in memory; writes go through the ordered async queue.
 */
public final class WorkerStore {

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final Map<UUID, WorkerRecord> byUuid = new ConcurrentHashMap<>();
    private final Map<Long, List<WorkerRecord>> byNode = new ConcurrentHashMap<>();

    public WorkerStore(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT worker_uuid, rarity, trait, stats, name, skin, level, exp, assigned_node_id, state "
                             + "FROM idlefarm_workers");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                long nodeId = rs.getLong("assigned_node_id");
                Long assigned = rs.wasNull() ? null : nodeId;
                WorkerRecord record = new WorkerRecord(
                        UUID.fromString(rs.getString("worker_uuid")),
                        Rarity.fromString(rs.getString("rarity")),
                        Trait.fromString(rs.getString("trait")),
                        WorkerStats.deserialize(rs.getString("stats")),
                        rs.getString("name"),
                        rs.getString("skin"),
                        rs.getInt("level"),
                        rs.getLong("exp"),
                        assigned,
                        rs.getString("state"));
                index(record);
            }
            plugin.getLogger().info("Loaded " + byUuid.size() + " workers.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load workers: " + e.getMessage());
        }
    }

    private void index(WorkerRecord record) {
        byUuid.put(record.getWorkerUuid(), record);
        if (record.getAssignedNodeId() != null) {
            byNode.computeIfAbsent(record.getAssignedNodeId(), k -> new ArrayList<>()).add(record);
        }
    }

    public WorkerRecord get(UUID workerUuid) {
        return byUuid.get(workerUuid);
    }

    public List<WorkerRecord> getAssigned(long nodeId) {
        return List.copyOf(byNode.getOrDefault(nodeId, List.of()));
    }

    public void insert(WorkerRecord record) {
        index(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idlefarm_workers (worker_uuid, rarity, trait, stats, name, skin, level, exp, assigned_node_id, state) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(insert, record);
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist worker " + record.getWorkerUuid() + ": " + e.getMessage());
            }
        });
    }

    public void update(WorkerRecord record) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE idlefarm_workers SET stats = ?, skin = ?, level = ?, exp = ?, assigned_node_id = ?, state = ? "
                                 + "WHERE worker_uuid = ?")) {
                update.setString(1, record.getStats().serialize());
                update.setString(2, record.getSkin());
                update.setInt(3, record.getLevel());
                update.setLong(4, record.getExp());
                if (record.getAssignedNodeId() == null) {
                    update.setNull(5, java.sql.Types.BIGINT);
                } else {
                    update.setLong(5, record.getAssignedNodeId());
                }
                update.setString(6, record.getState());
                update.setString(7, record.getWorkerUuid().toString());
                update.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update worker " + record.getWorkerUuid() + ": " + e.getMessage());
            }
        });
    }

    public void delete(WorkerRecord record) {
        byUuid.remove(record.getWorkerUuid());
        unassignIndex(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idlefarm_workers WHERE worker_uuid = ?")) {
                delete.setString(1, record.getWorkerUuid().toString());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete worker " + record.getWorkerUuid() + ": " + e.getMessage());
            }
        });
    }

    /** Call after mutating assignedNodeId to keep the node index in sync. */
    public void reindexAssignment(WorkerRecord record, Long previousNodeId) {
        if (previousNodeId != null) {
            List<WorkerRecord> list = byNode.get(previousNodeId);
            if (list != null) {
                list.remove(record);
            }
        }
        if (record.getAssignedNodeId() != null) {
            byNode.computeIfAbsent(record.getAssignedNodeId(), k -> new ArrayList<>()).add(record);
        }
        update(record);
    }

    private void unassignIndex(WorkerRecord record) {
        if (record.getAssignedNodeId() != null) {
            List<WorkerRecord> list = byNode.get(record.getAssignedNodeId());
            if (list != null) {
                list.remove(record);
            }
        }
    }

    private void bind(PreparedStatement insert, WorkerRecord record) throws SQLException {
        insert.setString(1, record.getWorkerUuid().toString());
        insert.setString(2, record.getRarity().name());
        insert.setString(3, record.getTrait().name());
        insert.setString(4, record.getStats().serialize());
        insert.setString(5, record.getName());
        insert.setString(6, record.getSkin());
        insert.setInt(7, record.getLevel());
        insert.setLong(8, record.getExp());
        if (record.getAssignedNodeId() == null) {
            insert.setNull(9, java.sql.Types.BIGINT);
        } else {
            insert.setLong(9, record.getAssignedNodeId());
        }
        insert.setString(10, record.getState());
    }
}
