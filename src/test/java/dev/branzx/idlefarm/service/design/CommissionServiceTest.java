package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.GameDesignService;
import dev.branzx.idlefarm.service.ProgressionRewards;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommissionServiceTest {

    @TempDir
    Path temp;

    @Test
    void boardIsStableAndAllowsOnlyOneFreeReroll() throws Exception {
        IdleFarmPlugin plugin = pluginWithCatalog();
        Database database = mock(Database.class);
        GameStateStore state = new GameStateStore(plugin, database);
        FocusService focus = mock(FocusService.class);
        WarehouseService warehouse = mock(WarehouseService.class);
        when(warehouse.getContents(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of());
        NodeRecord node = node(25);
        when(focus.focusedRecord(node.getOwnerUuid())).thenReturn(node);
        when(focus.isFocused(node)).thenReturn(true);

        CommissionService commissions = new CommissionService(plugin, database, state,
                mock(AuditService.class), mock(TelemetryService.class), rewards(),
                focus, mock(NodeBuildService.class), mock(ChronicleService.class),
                warehouse, (ignored, amount) -> { }, (ignored, amount) -> { });

        List<String> first = commissions.commissions(node.getOwnerUuid()).stream()
                .limit(3).map(GameDesignService.Commission::description).toList();
        List<String> second = commissions.commissions(node.getOwnerUuid()).stream()
                .limit(3).map(GameDesignService.Commission::description).toList();
        assertEquals(first, second);
        assertEquals(3, first.size());

        GameDesignService.Result rerolled =
                commissions.reroll(node.getOwnerUuid(), "slot_1");
        assertTrue(rerolled.success());
        List<String> after = commissions.commissions(node.getOwnerUuid()).stream()
                .limit(3).map(GameDesignService.Commission::description).toList();
        assertNotEquals(first.get(0), after.get(0));
        assertFalse(commissions.reroll(node.getOwnerUuid(), "slot_2").success());
    }

    private NodeRecord node(int level) {
        NodeRecord node = new NodeRecord(7, UUID.randomUUID(),
                new ChunkKey("world", 0, 0), NodeType.MINING, 1,
                NodeRecord.STATE_ACTIVE, 64, System.currentTimeMillis(), null);
        node.setExplorationLevel(level);
        return node;
    }

    private ProgressionRewards rewards() {
        return new ProgressionRewards(300, 100, 700, 100, 3,
                400, 600, 3, 3500, 2000, 200);
    }

    private IdleFarmPlugin pluginWithCatalog() throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/commissions.yml")) {
            Files.copy(source, temp.resolve("commissions.yml"));
        }
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CommissionServiceTest"));
        return plugin;
    }
}
