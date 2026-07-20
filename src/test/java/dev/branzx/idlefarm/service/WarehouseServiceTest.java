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
}
