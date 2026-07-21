package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarehouseServiceTest {

    @Test
    void depositAllLeavesWarehouseUntouchedWhenBundleDoesNotFit() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("warehouse.base-capacity", 2000)).thenReturn(5);

        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));
        UUID owner = UUID.randomUUID();
        assertEquals(3, warehouse.deposit(owner, "stone", 3));

        assertFalse(warehouse.depositAll(owner, Map.of("diamond", 2, "coal", 1)));
        assertEquals(Map.of("STONE", 3), warehouse.getContents(owner));
        assertEquals(2, warehouse.freeSpace(owner));
    }

    @Test
    void depositAllStoresCompleteBundleWhenItFits() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("warehouse.base-capacity", 2000)).thenReturn(5);

        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));
        UUID owner = UUID.randomUUID();

        assertTrue(warehouse.depositAll(owner, Map.of("diamond", 2, "coal", 3)));
        assertEquals(Map.of("DIAMOND", 2, "COAL", 3), warehouse.getContents(owner));
        assertEquals(0, warehouse.freeSpace(owner));
    }

    @Test
    void callersCannotMutateWarehouseWithoutCapacityAndPersistenceChecks() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("warehouse.base-capacity", 2000)).thenReturn(5);
        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));
        UUID owner = UUID.randomUUID();
        warehouse.deposit(owner, "stone", 1);

        assertThrows(UnsupportedOperationException.class,
                () -> warehouse.getContents(owner).put("DIAMOND", 999));
        assertEquals(Map.of("STONE", 1), warehouse.getContents(owner));
    }

    /** Vault tiny, Silo roomy — the shape the two lanes actually have. */
    private FileConfiguration pools(IdleFarmPlugin plugin, int vault, int silo) {
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getInt("warehouse.base-capacity", 2000)).thenReturn(vault);
        when(config.getInt("warehouse.silo.base-capacity", 120_000)).thenReturn(silo);
        when(config.getInt("warehouse.expand-step", 1000)).thenReturn(1000);
        when(config.getInt("warehouse.silo.expand-step", 20_000)).thenReturn(20_000);
        return config;
    }

    @Test
    void bulkCommonsAndDiscoveryFindsSpendSeparateCapacity() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        pools(plugin, 4, 100);
        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));
        UUID owner = UUID.randomUUID();

        // Filling the Silo with commons must not consume any Vault space.
        assertEquals(100, warehouse.deposit(owner, "cobblestone", 500));
        assertEquals(0, warehouse.siloFreeSpace(owner));
        assertEquals(4, warehouse.freeSpace(owner));

        assertEquals(4, warehouse.deposit(owner, "diamond", 9));
        assertEquals(Map.of("COBBLESTONE", 100, "DIAMOND", 4), warehouse.getContents(owner));
    }

    @Test
    void aFullSiloNeverBlocksRareFindsOnCollect() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        pools(plugin, 10, 8);
        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));

        dev.branzx.idlefarm.node.NodeRecord node = new dev.branzx.idlefarm.node.NodeRecord(
                7, UUID.randomUUID(),
                new dev.branzx.idlefarm.node.ChunkKey("world", 0, 0),
                dev.branzx.idlefarm.node.NodeType.MINING, 1, "STORAGE_FULL", 64,
                System.currentTimeMillis(), null);
        node.getStorage().put("DIAMOND", 2);
        node.getBulkStorage().put("COBBLESTONE", 50);

        assertEquals(10, warehouse.collectNode(node));

        // Silo caps the commons at 8; the diamonds still land in the Vault.
        assertEquals(Map.of("DIAMOND", 2, "COBBLESTONE", 8),
                warehouse.getContents(node.getOwnerUuid()));
        assertTrue(node.getStorage().isEmpty());
        assertEquals(42, node.getBulkStorage().get("COBBLESTONE"));
        assertEquals("ACTIVE", node.getState());
    }

    @Test
    void oneExpansionGrowsBothPools() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        pools(plugin, 2000, 120_000);
        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));
        UUID owner = UUID.randomUUID();

        assertEquals(120_000, warehouse.siloCapacity(owner));

        // Two expansions bought: capacity reflects the purchases in both pools.
        warehouse.restore(new WarehouseService.Snapshot(owner, 4000, ""));

        assertEquals(4000, warehouse.vaultCapacity(owner));
        assertEquals(160_000, warehouse.siloCapacity(owner));
    }

    @Test
    void bundlesAreJudgedPerPoolNotAgainstOneTotal() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        pools(plugin, 2, 100);
        WarehouseService warehouse = new WarehouseService(plugin, mock(Database.class));
        UUID owner = UUID.randomUUID();

        // Commons fit the Silo easily; the two diamonds exactly fill the Vault.
        assertTrue(warehouse.depositAll(owner, Map.of("cobblestone", 80, "diamond", 2)));

        // A third diamond no longer fits even though the Silo has room to spare.
        assertFalse(warehouse.depositAll(owner, Map.of("diamond", 1)));
        assertEquals(Map.of("COBBLESTONE", 80, "DIAMOND", 2), warehouse.getContents(owner));
    }
}
