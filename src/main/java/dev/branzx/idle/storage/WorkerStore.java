package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.worker.Rarity;
import dev.branzx.idle.worker.Trait;
import dev.branzx.idle.worker.WorkerRecord;
import dev.branzx.idle.worker.WorkerStats;

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

    private final IdlePlugin plugin;
    private final Database database;
    private final Map<UUID, WorkerRecord> byUuid = new ConcurrentHashMap<>();
    private final Map<Long, List<WorkerRecord>> byNode = new ConcurrentHashMap<>();
    // owner -> workers currently sitting in their virtual bag (state BAG)
    private final Map<UUID, List<WorkerRecord>> bagByOwner = new ConcurrentHashMap<>();

    public WorkerStore(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT worker_uuid, owner_uuid, rarity, trait, stats, name, skin, level, exp, "
                             + "assigned_node_id, state FROM idle_workers");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                long nodeId = rs.getLong("assigned_node_id");
                Long assigned = rs.wasNull() ? null : nodeId;
                String owner = rs.getString("owner_uuid");
                WorkerRecord record = new WorkerRecord(
                        UUID.fromString(rs.getString("worker_uuid")),
                        owner == null ? null : UUID.fromString(owner),
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
        if (record.isInBag() && record.getOwnerUuid() != null) {
            bagByOwner.computeIfAbsent(record.getOwnerUuid(), k -> new ArrayList<>()).add(record);
        }
    }

    public WorkerRecord get(UUID workerUuid) {
        return byUuid.get(workerUuid);
    }

    public List<WorkerRecord> getAssigned(long nodeId) {
        return List.copyOf(byNode.getOrDefault(nodeId, List.of()));
    }

    public List<WorkerRecord> getBag(UUID owner) {
        return List.copyOf(bagByOwner.getOrDefault(owner, List.of()));
    }

    public int bagCount(UUID owner) {
        List<WorkerRecord> list = bagByOwner.get(owner);
        return list == null ? 0 : list.size();
    }

    /** Move a worker into the owner's bag (state BAG). Persists. */
    public void moveToBag(WorkerRecord record, UUID owner) {
        removeFromBagIndex(record);
        Long previousNode = record.getAssignedNodeId();
        record.setAssignedNodeId(null);
        record.setOwnerUuid(owner);
        record.setState(WorkerRecord.STATE_BAG);
        if (previousNode != null) {
            List<WorkerRecord> list = byNode.get(previousNode);
            if (list != null) {
                list.remove(record);
            }
        }
        bagByOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(record);
        update(record);
    }

    /** Take a worker out of the bag into item form (for withdraw/trade). */
    public void moveToItem(WorkerRecord record) {
        removeFromBagIndex(record);
        record.setState(WorkerRecord.STATE_ITEM);
        record.setOwnerUuid(null);
        update(record);
    }

    private void removeFromBagIndex(WorkerRecord record) {
        if (record.getOwnerUuid() != null) {
            List<WorkerRecord> list = bagByOwner.get(record.getOwnerUuid());
            if (list != null) {
                list.remove(record);
            }
        }
    }

    public void insert(WorkerRecord record) {
        index(record);
        WorkerSnapshot snapshot = WorkerSnapshot.from(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idle_workers (worker_uuid, owner_uuid, rarity, trait, stats, name, skin, level, exp, assigned_node_id, state) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(insert, snapshot);
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist worker " + record.getWorkerUuid() + ": " + e.getMessage());
            }
        });
    }

    /** Mints a worker and debits its Coin price in one durable transaction. */
    public boolean insertWithCost(WorkerRecord record, PlayerData player, double cost) {
        WorkerSnapshot snapshot = WorkerSnapshot.from(record);
        double balanceAfter = player.getBalance() - cost;
        boolean committed = database.executeTransaction("mint worker " + snapshot.workerUuid(),
                connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idle_workers "
                            + "(worker_uuid, owner_uuid, rarity, trait, stats, name, skin, level, exp, "
                            + "assigned_node_id, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                bind(insert, snapshot);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_players SET balance = ? WHERE uuid = ?")) {
                update.setDouble(1, balanceAfter);
                update.setString(2, player.getUuid().toString());
                if (update.executeUpdate() != 1) throw new SQLException("Player row is missing");
            }
        });
        if (!committed) return false;
        index(record);
        player.addBalance(-cost);
        return true;
    }

    public void update(WorkerRecord record) {
        WorkerSnapshot snapshot = WorkerSnapshot.from(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE idle_workers SET owner_uuid = ?, stats = ?, name = ?, skin = ?, level = ?, "
                                 + "exp = ?, assigned_node_id = ?, state = ? WHERE worker_uuid = ?")) {
                if (snapshot.ownerUuid() == null) {
                    update.setNull(1, java.sql.Types.VARCHAR);
                } else {
                    update.setString(1, snapshot.ownerUuid().toString());
                }
                update.setString(2, snapshot.stats());
                update.setString(3, snapshot.name());
                update.setString(4, snapshot.skin());
                update.setInt(5, snapshot.level());
                update.setLong(6, snapshot.exp());
                if (snapshot.assignedNodeId() == null) {
                    update.setNull(7, java.sql.Types.BIGINT);
                } else {
                    update.setLong(7, snapshot.assignedNodeId());
                }
                update.setString(8, snapshot.state());
                update.setString(9, snapshot.workerUuid().toString());
                update.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update worker " + record.getWorkerUuid() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Consumes fuse materials and (on success) mints the result in one
     * ordered transaction, so a restart can never observe the materials and
     * the fused worker at the same time. Pass a null {@code minted} for a
     * failed fuse that only consumes the duplicate.
     */
    public void fuseSettle(List<WorkerRecord> consumed, WorkerRecord minted) {
        List<UUID> consumedIds = new ArrayList<>();
        for (WorkerRecord record : consumed) {
            byUuid.remove(record.getWorkerUuid());
            unassignIndex(record);
            removeFromBagIndex(record);
            consumedIds.add(record.getWorkerUuid());
        }
        WorkerSnapshot mintedSnapshot = minted == null ? null : WorkerSnapshot.from(minted);
        if (minted != null) {
            index(minted);
        }
        database.submitTransaction("fuse settlement", connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM idle_workers WHERE worker_uuid = ?")) {
                for (UUID id : consumedIds) {
                    delete.setString(1, id.toString());
                    delete.executeUpdate();
                }
            }
            if (mintedSnapshot != null) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO idle_workers (worker_uuid, owner_uuid, rarity, trait, stats, "
                                + "name, skin, level, exp, assigned_node_id, state) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    bind(insert, mintedSnapshot);
                    insert.executeUpdate();
                }
            }
        });
    }

    public void delete(WorkerRecord record) {
        byUuid.remove(record.getWorkerUuid());
        unassignIndex(record);
        removeFromBagIndex(record);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idle_workers WHERE worker_uuid = ?")) {
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

    private void bind(PreparedStatement insert, WorkerSnapshot snapshot) throws SQLException {
        insert.setString(1, snapshot.workerUuid().toString());
        if (snapshot.ownerUuid() == null) {
            insert.setNull(2, java.sql.Types.VARCHAR);
        } else {
            insert.setString(2, snapshot.ownerUuid().toString());
        }
        insert.setString(3, snapshot.rarity());
        insert.setString(4, snapshot.trait());
        insert.setString(5, snapshot.stats());
        insert.setString(6, snapshot.name());
        insert.setString(7, snapshot.skin());
        insert.setInt(8, snapshot.level());
        insert.setLong(9, snapshot.exp());
        if (snapshot.assignedNodeId() == null) {
            insert.setNull(10, java.sql.Types.BIGINT);
        } else {
            insert.setLong(10, snapshot.assignedNodeId());
        }
        insert.setString(11, snapshot.state());
    }

    private record WorkerSnapshot(UUID workerUuid, UUID ownerUuid, String rarity, String trait,
                                  String stats, String name, String skin, int level, long exp,
                                  Long assignedNodeId, String state) {
        private static WorkerSnapshot from(WorkerRecord record) {
            return new WorkerSnapshot(record.getWorkerUuid(), record.getOwnerUuid(),
                    record.getRarity().name(), record.getTrait().name(),
                    record.getStats().serialize(), record.getName(), record.getSkin(),
                    record.getLevel(), record.getExp(), record.getAssignedNodeId(),
                    record.getState());
        }
    }
}
