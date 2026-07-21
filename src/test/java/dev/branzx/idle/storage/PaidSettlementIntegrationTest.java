package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.worker.Rarity;
import dev.branzx.idle.worker.Trait;
import dev.branzx.idle.worker.WorkerRecord;
import dev.branzx.idle.worker.WorkerStats;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaidSettlementIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void nodeClaimAndWorkerMintCommitWithTheirCoinCosts() throws Exception {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PaidSettlementIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        Database database = new Database(plugin);
        database.init();
        try {
            UUID owner = UUID.randomUUID();
            PlayerDataStore players = new PlayerDataStore(plugin, database);
            PlayerData player = players.loadOrCreateSync(owner, "Tester");
            player.addBalance(1_000);
            players.saveSync(player);

            NodeStore nodes = new NodeStore(plugin, database);
            NodeRecord node = nodes.insert(owner, new ChunkKey("world", 4, 8),
                    NodeType.MINING, 64, player, 250);
            assertNotNull(node);
            assertEquals(750.0, player.getBalance());

            WorkerStore workers = new WorkerStore(plugin, database);
            WorkerRecord worker = new WorkerRecord(UUID.randomUUID(), owner, Rarity.COMMON,
                    Trait.BALANCED, new WorkerStats(8, 8, 8, 8),
                    "Pioneer", "Steve", 1, 0, null, WorkerRecord.STATE_BAG);
            assertTrue(workers.insertWithCost(worker, player, 100));
            assertEquals(650.0, player.getBalance());

            try (var connection = database.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT balance FROM idle_players WHERE uuid = ?")) {
                select.setString(1, owner.toString());
                try (var rs = select.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(650.0, rs.getDouble(1));
                }
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void unclaimRefundCommitsWithTheNodeDelete() throws Exception {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PaidSettlementIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        Database database = new Database(plugin);
        database.init();
        try {
            UUID owner = UUID.randomUUID();
            PlayerDataStore players = new PlayerDataStore(plugin, database);
            PlayerData player = players.loadOrCreateSync(owner, "Tester");
            player.addBalance(1_000);
            players.saveSync(player);

            NodeStore nodes = new NodeStore(plugin, database);
            NodeRecord node = nodes.insert(owner, new ChunkKey("world", 1, 1),
                    NodeType.MINING, 64, player, 400);
            assertNotNull(node);
            assertEquals(600.0, player.getBalance());

            assertTrue(nodes.deleteWithRefund(node, player, 200));
            assertEquals(800.0, player.getBalance());
            assertEquals(0, nodes.getByOwner(owner).size());

            try (var connection = database.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT COUNT(*) FROM idle_nodes WHERE id = ?")) {
                select.setLong(1, node.getId());
                try (var rs = select.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
            try (var connection = database.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT balance FROM idle_players WHERE uuid = ?")) {
                select.setString(1, owner.toString());
                try (var rs = select.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(800.0, rs.getDouble(1));
                }
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void fuseSettlementConsumesMaterialsAndMintsTheResultTogether() throws Exception {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PaidSettlementIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        Database database = new Database(plugin);
        database.init();
        try {
            WorkerStore workers = new WorkerStore(plugin, database);
            WorkerRecord first = new WorkerRecord(UUID.randomUUID(), null, Rarity.COMMON,
                    Trait.BALANCED, new WorkerStats(5, 5, 5, 5),
                    "Alpha", "Steve", 1, 0, null, WorkerRecord.STATE_ITEM);
            WorkerRecord second = new WorkerRecord(UUID.randomUUID(), null, Rarity.COMMON,
                    Trait.BALANCED, new WorkerStats(5, 5, 5, 5),
                    "Beta", "Steve", 1, 0, null, WorkerRecord.STATE_ITEM);
            workers.insert(first);
            workers.insert(second);
            WorkerRecord fused = new WorkerRecord(UUID.randomUUID(), null, Rarity.UNCOMMON,
                    Trait.BALANCED, new WorkerStats(9, 9, 9, 9),
                    "Gamma", "Steve", 1, 0, null, WorkerRecord.STATE_ITEM);

            workers.fuseSettle(java.util.List.of(first, second), fused);
            // Reload from disk after the ordered queue drains on shutdown.
            database.shutdown();

            Database reopened = new Database(plugin);
            reopened.init();
            try {
                WorkerStore reloaded = new WorkerStore(plugin, reopened);
                reloaded.loadAllSync();
                assertEquals(null, reloaded.get(first.getWorkerUuid()));
                assertEquals(null, reloaded.get(second.getWorkerUuid()));
                assertNotNull(reloaded.get(fused.getWorkerUuid()));
            } finally {
                reopened.shutdown();
            }
        } finally {
            database.shutdown();
        }
    }
}
