package dev.branzx.idlefarm.storage;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.Trait;
import dev.branzx.idlefarm.worker.WorkerRecord;
import dev.branzx.idlefarm.worker.WorkerStats;
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
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
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
                         "SELECT balance FROM idlefarm_players WHERE uuid = ?")) {
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
}
