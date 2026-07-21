package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DropTableServiceTest {

    @TempDir Path temp;
    private DropTableService drops;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        config = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml"));
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DropTableServiceTest"));
        drops = new DropTableService(plugin);
        drops.load();
    }

    @Test
    void draftDoesNotAffectRuntimeUntilPublishedAndCanRollback() {
        double published = drops.table(NodeType.MINING, 1).get("coal");

        assertTrue(drops.setWeight("mining.bracket-1", "coal", published + 7));
        assertEquals(published, drops.table(NodeType.MINING, 1).get("coal"));
        assertTrue(drops.status().draftDirty());
        assertTrue(drops.validateDraft().isEmpty());

        var publish = drops.publish();
        assertTrue(publish.success(), publish.errors().toString());
        assertEquals(published + 7, drops.table(NodeType.MINING, 1).get("coal"));
        assertEquals(1, drops.status().rollbackRevisions());

        var rollback = drops.rollback();
        assertTrue(rollback.success(), rollback.error());
        assertEquals(published, drops.table(NodeType.MINING, 1).get("coal"));
        assertFalse(drops.status().draftDirty());
    }

    @Test
    void invalidDraftCannotBePublished() {
        assertTrue(drops.setWeight("mining.bracket-1", "cobblestone", 0));
        assertTrue(drops.setWeight("mining.bracket-1", "coal", 0));
        assertTrue(drops.setWeight("mining.bracket-1", "raw_iron", 0));

        assertFalse(drops.validateDraft().isEmpty());
        assertFalse(drops.publish().success());
        assertFalse(drops.table(NodeType.MINING, 1).isEmpty());
    }

    @Test
    void everyEmittedResourceHasEconomyContract() {
        var policies = drops.resourcePolicies(true);
        assertFalse(policies.isEmpty());
        assertTrue(policies.values().stream().allMatch(DropTableService.ResourcePolicy::complete));
        assertTrue(policies.containsKey("DIAMOND"));
        assertTrue(policies.get("DIAMOND").cap().contains("DAILY"));
        assertTrue(policies.containsKey("AETHER_ORE"));
        assertEquals(101, policies.get("AETHER_ORE").unlockLevel());
    }

    @Test
    void stagedKillSwitchStopsOnlyFutureFrontierRolls() {
        java.util.UUID owner = java.util.UUID.randomUUID();
        config.set("frontier.enabled", true);
        config.set("frontier.sink-gates.profession", true);
        config.set("frontier.sink-gates.equipment", true);
        config.set("frontier.sink-gates.repair", true);
        config.set("frontier.sink-gates.project", true);
        config.set("live-ops.features.frontier-drops.enabled", true);
        config.set("live-ops.features.frontier-drops.rollout-percent", 100);

        assertTrue(drops.table(NodeType.MINING, 1, 101, owner).containsKey("AETHER_ORE"));

        config.set("live-ops.features.frontier-drops.enabled", false);
        assertFalse(drops.table(NodeType.MINING, 1, 101, owner).containsKey("AETHER_ORE"));
        // Drop-table control has no Warehouse/inventory mutation path.
        assertTrue(drops.isCustomMaterial("AETHER_ORE"));
    }
}
