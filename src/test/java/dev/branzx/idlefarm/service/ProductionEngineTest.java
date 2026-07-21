package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.Trait;
import dev.branzx.idlefarm.worker.WorkerRecord;
import dev.branzx.idlefarm.worker.WorkerStats;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductionEngineTest {

    @Test
    void unstaffedNodeStaysIdleAndPersistsItsTickAnchor() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("production.buffer-capacity-per-tier", 256)).thenReturn(256);

        NodeStore nodeStore = mock(NodeStore.class);
        WorkerStore workerStore = mock(WorkerStore.class);
        WorkerService workerService = mock(WorkerService.class);
        long oldAnchor = System.currentTimeMillis() - 3_600_000;
        NodeRecord node = new NodeRecord(42, UUID.randomUUID(),
                new ChunkKey("world", 0, 0), NodeType.MINING, 1,
                NodeRecord.STATE_ACTIVE, 64, oldAnchor, null);
        when(nodeStore.getAll()).thenReturn(List.of(node));
        when(workerStore.getAssigned(node.getId())).thenReturn(List.of());

        new ProductionEngine(plugin, nodeStore, workerStore, workerService,
                null, null, null, null, null, null, null).run();

        assertEquals(NodeRecord.STATE_IDLE, node.getState());
        assertEquals(0, node.storageTotal());
        assertTrue(node.getLastTickAt() > oldAnchor);
        assertTrue(node.getBulkLastTickAt() > oldAnchor);
        verify(nodeStore).updateProduction(node);
    }

    @Test
    void bulkLaneAccruesDeterministicCommonsIndependentlyOfDiscovery() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("production.buffer-capacity-per-tier", 256)).thenReturn(256);
        // Discovery lane disabled (base rate 0) to isolate the bulk lane.
        when(config.getDouble("production.base-items-per-hour.mining", 30.0)).thenReturn(0.0);
        when(config.getDouble("production.bulk.base-per-hour.mining", 0.0)).thenReturn(70.0);
        when(config.getDouble("production.level-power-per-level", 0.02)).thenReturn(0.02);
        when(config.getDouble("production.bulk.diligence-factor", 2.0)).thenReturn(2.0);
        when(config.getDouble("production.bulk.rarity-mult.common", 1.0)).thenReturn(1.0);
        when(config.getInt("production.bulk.buffer-hours.tier-1", 8)).thenReturn(8);
        when(config.getInt("production.bulk.buffer-hours.min", 4)).thenReturn(4);

        NodeStore nodeStore = mock(NodeStore.class);
        WorkerStore workerStore = mock(WorkerStore.class);
        WorkerService workerService = mock(WorkerService.class);
        long oldAnchor = System.currentTimeMillis() - 3_600_000;
        NodeRecord node = new NodeRecord(42, UUID.randomUUID(),
                new ChunkKey("world", 0, 0), NodeType.MINING, 1,
                NodeRecord.STATE_ACTIVE, 64, oldAnchor, null);
        WorkerRecord worker = new WorkerRecord(UUID.randomUUID(), node.getOwnerUuid(),
                Rarity.COMMON, Trait.BALANCED, new WorkerStats(0, 0, 0, 0),
                "Worker", "Steve", 1, 0, node.getId(), WorkerRecord.STATE_WORKING);
        when(nodeStore.getAll()).thenReturn(List.of(node));
        when(workerStore.getAssigned(node.getId())).thenReturn(List.of(worker));

        new ProductionEngine(plugin, nodeStore, workerStore, workerService,
                null, null, null, null, null, null, null).run();

        // One Common Lv.1 worker for one hour: 70 × 1.02 = 71.4 → floor 71,
        // all routed to the default mining common (no commons config mocked).
        assertEquals(71, node.bulkStorageTotal());
        assertEquals(71, node.getBulkStorage().get("COBBLESTONE"));
        assertEquals(0, node.storageTotal());
        assertTrue(node.getBulkLastTickAt() > oldAnchor);
        assertEquals(NodeRecord.STATE_ACTIVE, node.getState());
        verify(nodeStore).updateProduction(node);
    }
}
