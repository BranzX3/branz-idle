package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.Trait;
import dev.branzx.idlefarm.worker.WorkerRecord;
import dev.branzx.idlefarm.worker.WorkerStats;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerServiceTest {

    @Test
    void grantExpPersistsProgressEvenWithoutLevelUp() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getLong("workers.exp-per-level-base", 100)).thenReturn(100L);

        WorkerStore store = mock(WorkerStore.class);
        WorkerService service = new WorkerService(plugin, store,
                mock(PlayerDataStore.class), mock(Database.class),
                new NamespacedKey("idlefarm", "worker_uuid"));
        WorkerRecord worker = new WorkerRecord(UUID.randomUUID(), UUID.randomUUID(),
                Rarity.COMMON, Trait.BALANCED, new WorkerStats(5, 5, 5, 5),
                "Milo", "Steve", 1, 0, 1L, WorkerRecord.STATE_WORKING);

        service.grantExp(worker, 50);

        assertEquals(1, worker.getLevel());
        assertEquals(50, worker.getExp());
        verify(store).update(worker);
    }

    @Test
    void fuseRejectsTheSameWorkerInBothSlots() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getConfig()).thenReturn(mock(FileConfiguration.class));
        WorkerStore store = mock(WorkerStore.class);
        WorkerService service = new WorkerService(plugin, store,
                mock(PlayerDataStore.class), mock(Database.class),
                new NamespacedKey("idlefarm", "worker_uuid"));
        WorkerRecord worker = new WorkerRecord(UUID.randomUUID(), UUID.randomUUID(),
                Rarity.COMMON, Trait.BALANCED, new WorkerStats(5, 5, 5, 5),
                "Milo", "Steve", 1, 0, null, WorkerRecord.STATE_BAG);

        WorkerService.Result result = service.fuse(worker.getOwnerUuid(), List.of(worker, worker));

        assertFalse(result.success());
        verify(store, never()).delete(worker);
    }
}
