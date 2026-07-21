package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.service.ProgressionRewards;
import dev.branzx.idle.service.WarehouseService;
import dev.branzx.idle.service.design.ChronicleService;
import dev.branzx.idle.service.design.CommissionService;
import dev.branzx.idle.service.design.FocusService;
import dev.branzx.idle.service.design.NodeBuildService;
import dev.branzx.idle.service.design.ProjectService;
import dev.branzx.idle.service.design.SeasonService;
import dev.branzx.idle.service.design.SeasonalChronicleService;
import dev.branzx.idle.service.design.TelemetryService;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementFaultInjectionIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void failedClaimLeavesNeitherNodeNorCoinChargeAfterRestart() throws Exception {
        IdlePlugin plugin = sqlitePlugin(temp.resolve("claim"));
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        try {
            PlayerDataStore players = new PlayerDataStore(plugin, database);
            PlayerData player = players.loadOrCreateSync(owner, "ClaimFault");
            player.addBalance(1_000);
            players.saveSync(player);

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_claim_failure
                        BEFORE INSERT ON idle_nodes
                        BEGIN SELECT RAISE(FAIL, 'injected claim failure'); END
                        """);
            }

            NodeStore nodes = new NodeStore(plugin, database);
            assertNull(nodes.insert(owner, new ChunkKey("world", 3, 9),
                    NodeType.MINING, 64, player, 250));
            assertEquals(1_000.0, player.getBalance());
            assertEquals(0, nodes.getAll().size());
        } finally {
            database.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            PlayerDataStore players = new PlayerDataStore(plugin, restarted);
            PlayerData reloaded = players.loadOrCreateSync(owner, "ClaimFault");
            NodeStore nodes = new NodeStore(plugin, restarted);
            nodes.loadAllSync();
            assertEquals(1_000.0, reloaded.getBalance());
            assertEquals(0, nodes.getAll().size());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void failedDeliveryCommissionRestoresWarehouseAndRemainsClaimableAfterRestart()
            throws Exception {
        Path folder = temp.resolve("commission");
        IdlePlugin plugin = sqlitePlugin(folder);
        try (InputStream source = getClass().getResourceAsStream("/commissions.yml")) {
            Files.createDirectories(folder);
            Files.copy(source, folder.resolve("commissions.yml"));
        }
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        String day = dev.branzx.idle.service.design.GameClock.dayKey();
        try {
            GameStateStore state = new GameStateStore(plugin, database);
            WarehouseService warehouse = new WarehouseService(plugin, database);
            warehouse.deposit(owner, "OAK_LOG", 128);
            state.put(owner, "DAILY", day, "commission_slot_1", "delivery");
            state.put(owner, "DAILY", day, "commission_slot_1_progress", "0");

            NodeRecord focused = node(owner, 25);
            FocusService focus = mock(FocusService.class);
            when(focus.focusedRecord(owner)).thenReturn(focused);
            when(focus.isFocused(focused)).thenReturn(true);
            ChronicleService chronicle = mock(ChronicleService.class);
            when(chronicle.stagePointsGain(owner, 3)).thenReturn(
                    state.prepareIncrement(owner, "ACCOUNT", "-", "chronicle_points", 3));

            CommissionService commissions = new CommissionService(plugin, database, state,
                    mock(AuditService.class), mock(TelemetryService.class), rewards(),
                    focus, mock(NodeBuildService.class), chronicle,
                    mock(SeasonalChronicleService.class), warehouse,
                    (ignored, amount) -> { }, (ignored, amount) -> { });

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_commission_failure
                        BEFORE INSERT ON idle_game_state
                        WHEN NEW.state_key = 'commission_slot_1_claimed'
                        BEGIN SELECT RAISE(FAIL, 'injected commission failure'); END
                        """);
            }

            GameDesignService.Result result = commissions.claimCommission(owner, "slot_1");
            assertFalse(result.success());
            assertEquals(128, warehouse.getContents(owner).get("OAK_LOG"));
            assertNull(state.get(owner, "DAILY", day, "commission_slot_1_claimed"));
        } finally {
            database.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            GameStateStore state = new GameStateStore(plugin, restarted);
            state.loadAllSync();
            WarehouseService warehouse = new WarehouseService(plugin, restarted);
            warehouse.loadAllSync();
            assertEquals(128, warehouse.getContents(owner).get("OAK_LOG"));
            assertNull(state.get(owner, "DAILY", day, "commission_slot_1_claimed"));
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void failedProjectContributionRestoresWarehouseAndProgressAfterRestart()
            throws Exception {
        Path folder = temp.resolve("project");
        IdlePlugin plugin = sqlitePlugin(folder);
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        try {
            GameStateStore state = new GameStateStore(plugin, database);
            WarehouseService warehouse = new WarehouseService(plugin, database);
            warehouse.deposit(owner, "OAK_LOG", 128);
            SeasonService seasons = mock(SeasonService.class);
            when(seasons.id()).thenReturn("test-season");

            ProjectService projects = new ProjectService(plugin, database, state,
                    mock(AuditService.class), mock(TelemetryService.class), seasons,
                    mock(ChronicleService.class), mock(SeasonalChronicleService.class),
                    warehouse, mock(NodeStore.class), (ignored, amount) -> { });

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_project_failure
                        BEFORE INSERT ON idle_game_state
                        WHEN NEW.scope = 'PROJECT' AND NEW.state_key = 'progress'
                        BEGIN SELECT RAISE(FAIL, 'injected project failure'); END
                        """);
            }

            GameDesignService.Result result =
                    projects.contributeProject(owner, "storehouse", 64);
            assertFalse(result.success());
            assertEquals(128, warehouse.getContents(owner).get("OAK_LOG"));
            assertNull(state.get(owner, "PROJECT", "storehouse", "progress"));
        } finally {
            database.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            GameStateStore state = new GameStateStore(plugin, restarted);
            state.loadAllSync();
            WarehouseService warehouse = new WarehouseService(plugin, restarted);
            warehouse.loadAllSync();
            assertEquals(128, warehouse.getContents(owner).get("OAK_LOG"));
            assertNull(state.get(owner, "PROJECT", "storehouse", "progress"));
        } finally {
            restarted.shutdown();
        }
    }

    private IdlePlugin sqlitePlugin(Path folder) {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SettlementFaultInjectionTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");
        when(config.getInt("warehouse.base-capacity", 2000)).thenReturn(2000);
        return plugin;
    }

    private NodeRecord node(UUID owner, int level) {
        NodeRecord node = new NodeRecord(7, owner, new ChunkKey("world", 0, 0),
                NodeType.MINING, 1, NodeRecord.STATE_ACTIVE, 64,
                System.currentTimeMillis(), null);
        node.setExplorationLevel(level);
        return node;
    }

    private ProgressionRewards rewards() {
        return new ProgressionRewards(300, 100, 700, 100, 3,
                400, 600, 3, 3500, 2000, 200);
    }
}
