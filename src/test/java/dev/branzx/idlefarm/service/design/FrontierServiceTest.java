package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontierServiceTest {

    @Test
    void craftingTrainingDurabilityAndRepairCreateRepeatableSinks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("frontier.enabled", true);
        config.set("frontier.sink-gates.profession", true);
        config.set("frontier.sink-gates.equipment", true);
        config.set("frontier.sink-gates.repair", true);
        config.set("frontier.sink-gates.project", true);
        config.set("live-ops.features.frontier.enabled", true);
        config.set("live-ops.features.frontier.rollout-percent", 100);
        config.set("warehouse.base-capacity", 2000);
        config.set("frontier.equipment.tier-1.craft-cost", 12);
        config.set("frontier.equipment.tier-1.durability", 500);
        config.set("frontier.equipment.items-per-durability", 25);
        config.set("frontier.equipment.repair-per-material", 25);

        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FrontierServiceTest"));
        Database database = mock(Database.class);
        GameStateStore state = new GameStateStore(plugin, database);
        WarehouseService warehouse = new WarehouseService(plugin, database);
        FrontierService service = new FrontierService(plugin, state, warehouse,
                new FeatureControlService(plugin), mock(TelemetryService.class));
        UUID owner = UUID.randomUUID();
        NodeRecord node = new NodeRecord(7, owner, new ChunkKey("world", 1, 1),
                NodeType.MINING, 1, "ACTIVE", 101, System.currentTimeMillis(), null);
        node.setExplorationLevel(101);
        warehouse.deposit(owner, "AETHER_ORE", 100);

        assertTrue(service.craft(owner, node, 1).success());
        assertEquals(88, warehouse.getContents(owner).get("AETHER_ORE"));
        FrontierService.Equipment equipment = service.equipment(owner, node);
        assertNotNull(equipment);
        assertEquals(500, equipment.durability());
        assertTrue(service.productionMultiplier(node) > 1);

        service.consumeDurability(node, 50);
        assertEquals(498, service.equipment(owner, node).durability());
        assertTrue(service.repair(owner, node).success());
        assertEquals(500, service.equipment(owner, node).durability());
        assertEquals(87, warehouse.getContents(owner).get("AETHER_ORE"));

        assertTrue(service.train(owner, NodeType.MINING, "AETHER_ORE", 10).success());
        assertEquals(77, warehouse.getContents(owner).get("AETHER_ORE"));
        assertTrue(service.profession(owner, NodeType.MINING).exp() >= 200);
    }

    @Test
    void allFifteenMaterialsHaveCraftRepairAndTrainingPaths() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        FrontierService service = new FrontierService(plugin, mock(GameStateStore.class),
                mock(WarehouseService.class), mock(FeatureControlService.class),
                mock(TelemetryService.class));

        long materials = java.util.Arrays.stream(NodeType.values())
                .filter(NodeType::isProduction)
                .flatMap(type -> service.recipes(type).stream())
                .flatMap(recipe -> recipe.materials().keySet().stream())
                .distinct().count();
        assertEquals(15, materials);
    }
}
