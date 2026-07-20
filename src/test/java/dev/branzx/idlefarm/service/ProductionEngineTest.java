package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
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
        verify(nodeStore).updateProduction(node);
    }
}
