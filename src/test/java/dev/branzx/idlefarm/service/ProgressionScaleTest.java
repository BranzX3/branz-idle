package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProgressionScaleTest {

    @Test
    void levelOneToOneHundredCostsDesignBudget() {
        ProgressionScale scale = scaleWithDesignDefaults();
        long total = 0;
        for (int level = 1; level < 100; level++) {
            total += scale.expForNextLevel(level);
        }
        assertEquals(71_280, total);
        assertEquals(0, scale.expForNextLevel(100));
    }

    @Test
    void bracketsPreserveLaunchTenAndAllowManualPostHundredScale() {
        ProgressionScale scale = scaleWithDesignDefaults();
        assertEquals(1, scale.bracket(1));
        assertEquals(2, scale.bracket(10));
        assertEquals(2, scale.bracket(19));
        assertEquals(3, scale.bracket(20));
        assertEquals(10, scale.bracket(100));
        assertEquals(11, scale.bracket(101));
        assertEquals(21, scale.bracket(200));
        assertEquals(100, scale.bracket(1000));
        assertEquals(1000, scale.adminLevelCap());
    }

    @Test
    void tierScalesBufferQuantityLinearly() {
        ProgressionScale scale = scaleWithDesignDefaults();
        NodeRecord node = new NodeRecord(1, UUID.randomUUID(),
                new ChunkKey("world", 0, 0), NodeType.MINING, 5,
                "ACTIVE", 64, System.currentTimeMillis(), null);
        assertEquals(1_280, scale.bufferCapacity(node));
    }

    @Test
    void frontierRequiresEveryDurableSinkGate() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getBoolean("frontier.enabled", false)).thenReturn(true);
        when(config.getInt("frontier.level-cap", 200)).thenReturn(200);
        when(config.getInt("exploration.level-cap", 100)).thenReturn(100);
        when(config.getBoolean("frontier.sink-gates.profession", false)).thenReturn(true);
        when(config.getBoolean("frontier.sink-gates.equipment", false)).thenReturn(true);
        when(config.getBoolean("frontier.sink-gates.repair", false)).thenReturn(true);
        when(config.getBoolean("frontier.sink-gates.project", false)).thenReturn(false);
        assertEquals(100, new ProgressionScale(plugin).levelCap());

        when(config.getBoolean("frontier.sink-gates.project", false)).thenReturn(true);
        assertEquals(200, new ProgressionScale(plugin).levelCap());
    }

    @Test
    void bulkConfigValidatorFlagsMissingBaseRateButAcceptsDisabledLanes() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        // Unstubbed getDouble returns 0.0 => every production type reads as an
        // intentionally disabled lane, so a fully-zero config is clean.
        assertTrue(new ProgressionScale(plugin).validateBulkConfig().isEmpty());

        // A negative sentinel means the base-per-hour key is absent entirely.
        when(config.getDouble("production.bulk.base-per-hour.mining", -1)).thenReturn(-1.0);
        var problems = new ProgressionScale(plugin).validateBulkConfig();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("MINING"));
    }

    private ProgressionScale scaleWithDesignDefaults() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getBoolean("frontier.enabled", false)).thenReturn(false);
        when(config.getInt("exploration.level-cap", 100)).thenReturn(100);
        when(config.getLong("exploration.exp-curve.base", 120)).thenReturn(120L);
        when(config.getLong("exploration.exp-curve.per-level", 12)).thenReturn(12L);
        when(config.getInt("exploration.bracket-size", 10)).thenReturn(10);
        when(config.getInt("exploration.vanilla-brackets", 10)).thenReturn(10);
        when(config.getInt("exploration.max-brackets", 100)).thenReturn(100);
        when(config.getInt("exploration.admin-level-cap", 1000)).thenReturn(1000);
        when(config.getInt("production.buffer-capacity-per-tier", 256)).thenReturn(256);
        return new ProgressionScale(plugin);
    }
}
