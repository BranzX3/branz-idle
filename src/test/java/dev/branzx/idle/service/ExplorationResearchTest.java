package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.Rarity;
import dev.branzx.idle.worker.Trait;
import dev.branzx.idle.worker.WorkerRecord;
import dev.branzx.idle.worker.WorkerStats;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExplorationResearchTest {

    @Test
    void fullBufferResearchKeepsFractionalProgressAcrossTicks() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("exploration.level-cap", 100)).thenReturn(100);
        when(config.getLong("exploration.exp-curve.base", 120)).thenReturn(120L);
        when(config.getLong("exploration.exp-curve.per-level", 12)).thenReturn(12L);
        when(config.getInt("exploration.passive-research.daily-cap", 900)).thenReturn(900);
        when(config.getLong("exploration.passive-research.rested-hours", 48)).thenReturn(48L);
        when(config.getDouble("exploration.passive-research.base-per-hour", 75.0)).thenReturn(75.0);
        when(config.getDouble("exploration.passive-research.extra-worker-bonus", 0.15)).thenReturn(0.15);
        when(config.getDouble("exploration.passive-research.level-power-per-level", 0.005))
                .thenReturn(0.005);
        when(config.getDouble("exploration.passive-research.full-buffer-multiplier", 0.25))
                .thenReturn(0.25);
        when(config.getDouble("exploration.passive-research.rarity-factor.common", 1.0))
                .thenReturn(1.0);

        long start = 1_800_000_000_000L;
        NodeRecord node = new NodeRecord(7, UUID.randomUUID(),
                new ChunkKey("world", 0, 0), NodeType.MINING, 1,
                NodeRecord.STATE_STORAGE_FULL, 64, start, null);
        node.setExplorationLevel(1);
        WorkerRecord worker = new WorkerRecord(UUID.randomUUID(), node.getOwnerUuid(),
                Rarity.COMMON, Trait.BALANCED, new WorkerStats(0, 0, 0, 0),
                "Researcher", "Steve", 0, 0, node.getId(), WorkerRecord.STATE_STOP);

        ExplorationService service = new ExplorationService(plugin, mock(Database.class),
                mock(NodeStore.class), mock(WorkerStore.class));

        assertEquals(0, service.advancePassiveResearch(node, List.of(worker),
                start + 60_000, true));
        assertEquals(0, service.advancePassiveResearch(node, List.of(worker),
                start + 120_000, true));
        assertEquals(0, service.advancePassiveResearch(node, List.of(worker),
                start + 180_000, true));
        assertEquals(1, service.advancePassiveResearch(node, List.of(worker),
                start + 240_000, true));
        assertEquals(1, node.getExplorationExp());
    }
}
