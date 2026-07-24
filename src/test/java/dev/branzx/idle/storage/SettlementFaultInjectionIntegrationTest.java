package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.ExplorationService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementFaultInjectionIntegrationTest {

    /** Mirrors {@code ProjectService.SERVER_SCOPE}: the shared owner row. */
    private static final UUID SERVER_SCOPE = new UUID(0, 0);

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
            players.deposit(owner, 1_000);

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

    @Test
    void failedServerProjectContributionRestoresWarehouseAndDailyCapAfterRestart()
            throws Exception {
        Path folder = temp.resolve("server-project");
        IdlePlugin plugin = sqlitePlugin(folder);
        FileConfiguration config = plugin.getConfig();
        when(config.getString("projects.server.material", "COBBLESTONE"))
                .thenReturn("COBBLESTONE");
        when(config.getInt("projects.server.target", 100_000)).thenReturn(100_000);
        when(config.getInt("projects.server.daily-cap", 1_024)).thenReturn(1_024);
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        String day = dev.branzx.idle.service.design.GameClock.dayKey();
        try {
            GameStateStore state = new GameStateStore(plugin, database);
            WarehouseService warehouse = new WarehouseService(plugin, database);
            warehouse.deposit(owner, "COBBLESTONE", 512);
            SeasonService seasons = mock(SeasonService.class);
            when(seasons.id()).thenReturn("test-season");

            ProjectService projects = new ProjectService(plugin, database, state,
                    mock(AuditService.class), mock(TelemetryService.class), seasons,
                    mock(ChronicleService.class), mock(SeasonalChronicleService.class),
                    warehouse, mock(NodeStore.class), (ignored, amount) -> { });

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_server_project_failure
                        BEFORE INSERT ON idle_game_state
                        WHEN NEW.scope = 'PROJECT' AND NEW.state_key = 'progress'
                        BEGIN SELECT RAISE(FAIL, 'injected server project failure'); END
                        """);
            }

            GameDesignService.Result result = projects.contributeServerProject(owner, 256);
            assertFalse(result.success());
            assertEquals(512, warehouse.getContents(owner).get("COBBLESTONE"));
            // The daily cap must not be spent by a contribution that never landed.
            assertEquals(0, state.getInt(owner, "DAILY", day, "server_project_contribution", 0));
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
            assertEquals(512, warehouse.getContents(owner).get("COBBLESTONE"));
            assertEquals(0, state.getInt(owner, "DAILY", day, "server_project_contribution", 0));
            assertNull(state.get(SERVER_SCOPE, "PROJECT", "season_test-season", "progress"));
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void failedExpeditionPreparationRestoresWarehouseAfterRestart() throws Exception {
        Path folder = temp.resolve("preparation");
        IdlePlugin plugin = sqlitePlugin(folder);
        when(plugin.getConfig().getInt("exploration.preparation-kit-cost", 16)).thenReturn(16);
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        try {
            GameStateStore state = new GameStateStore(plugin, database);
            WarehouseService warehouse = new WarehouseService(plugin, database);
            warehouse.deposit(owner, "COBBLESTONE", 64);
            SeasonService seasons = mock(SeasonService.class);
            when(seasons.id()).thenReturn("test-season");

            ProjectService projects = new ProjectService(plugin, database, state,
                    mock(AuditService.class), mock(TelemetryService.class), seasons,
                    mock(ChronicleService.class), mock(SeasonalChronicleService.class),
                    warehouse, mock(NodeStore.class), (ignored, amount) -> { });

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_preparation_failure
                        BEFORE INSERT ON idle_game_state
                        WHEN NEW.state_key = 'next_preparation'
                        BEGIN SELECT RAISE(FAIL, 'injected preparation failure'); END
                        """);
            }

            GameDesignService.Result result =
                    projects.prepareExpedition(owner, node(owner, 25), "speed");
            assertFalse(result.success());
            assertEquals(64, warehouse.getContents(owner).get("COBBLESTONE"));
            assertNull(state.get(owner, "NODE", "7", "next_preparation"));
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
            assertEquals(64, warehouse.getContents(owner).get("COBBLESTONE"));
            assertNull(state.get(owner, "NODE", "7", "next_preparation"));
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void failedNodeCollectionKeepsTheBufferAndCannotLeakLootIntoALaterWrite()
            throws Exception {
        IdlePlugin plugin = sqlitePlugin(temp.resolve("collect"));
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        try {
            PlayerDataStore players = new PlayerDataStore(plugin, database);
            PlayerData player = players.loadOrCreateSync(owner, "CollectFault");
            players.saveSync(player);
            NodeStore nodes = new NodeStore(plugin, database);
            NodeRecord node = nodes.insert(owner, new ChunkKey("world", 4, 4),
                    NodeType.MINING, 64, player, 0);
            node.getStorage().put("DIAMOND", 12);
            nodes.updateProduction(node);
            WarehouseService warehouse = new WarehouseService(plugin, database);
            drain(database);

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_collect_failure
                        BEFORE UPDATE ON idle_nodes
                        BEGIN SELECT RAISE(FAIL, 'injected collect failure'); END
                        """);
            }

            warehouse.collectNode(node);
            drain(database);

            // The durable rows rolled back together, so the caches must agree
            // with them: the buffer still owns the loot, the Warehouse does not.
            assertEquals(12, node.getStorage().get("DIAMOND"));
            assertNull(warehouse.getContents(owner).get("DIAMOND"));

            // A later, unrelated Warehouse write serialises the whole map. If
            // the failed collect had stayed in the cache, this write would
            // persist the loot while the node buffer row still holds it.
            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER inject_collect_failure");
            }
            warehouse.deposit(owner, "EMERALD", 1);
            drain(database);
        } finally {
            database.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            WarehouseService warehouse = new WarehouseService(plugin, restarted);
            warehouse.loadAllSync();
            NodeStore nodes = new NodeStore(plugin, restarted);
            nodes.loadAllSync();
            assertNull(warehouse.getContents(owner).get("DIAMOND"));
            assertEquals(12, nodes.getAll().get(0).getStorage().get("DIAMOND"));
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void failedExplorationLootClaimKeepsTheEventClaimableAndDepositsNothing()
            throws Exception {
        IdlePlugin plugin = sqlitePlugin(temp.resolve("loot"));
        Database database = new Database(plugin);
        database.init();
        UUID owner = UUID.randomUUID();
        try {
            PlayerDataStore players = new PlayerDataStore(plugin, database);
            PlayerData player = players.loadOrCreateSync(owner, "LootFault");
            players.saveSync(player);
            NodeStore nodes = new NodeStore(plugin, database);
            NodeRecord node = nodes.insert(owner, new ChunkKey("world", 5, 5),
                    NodeType.MINING, 64, player, 0);
            long now = System.currentTimeMillis();
            try (var connection = database.getConnection();
                 java.sql.PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idle_exploration_events (id, node_id, event_type, state, "
                                 + "spawned_at, expires_at, started_at, ends_at, worker_uuids, "
                                 + "outcome_grade, loot) VALUES (1, ?, 'EXPEDITION', 'COMPLETED', "
                                 + "?, ?, ?, ?, '', 'GOOD', 'DIAMOND:5')")) {
                insert.setLong(1, node.getId());
                insert.setTimestamp(2, new java.sql.Timestamp(now - 60_000));
                insert.setTimestamp(3, new java.sql.Timestamp(now + 600_000));
                insert.setTimestamp(4, new java.sql.Timestamp(now - 60_000));
                insert.setTimestamp(5, new java.sql.Timestamp(now - 1_000));
                insert.executeUpdate();
            }
            ExplorationService exploration = new ExplorationService(plugin, database, nodes,
                    mock(WorkerStore.class));
            exploration.loadAllSync();
            WarehouseService warehouse = new WarehouseService(plugin, database);

            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TRIGGER inject_loot_claim_failure
                        BEFORE DELETE ON idle_exploration_events
                        BEGIN SELECT RAISE(FAIL, 'injected loot claim failure'); END
                        """);
            }

            var failed = exploration.claimToWarehouse(node, warehouse);
            assertFalse(failed.success());
            assertNull(warehouse.getContents(owner).get("DIAMOND"));

            // The event is still there, so the player can claim it once the
            // database recovers — and only then does the loot exist.
            try (var connection = database.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER inject_loot_claim_failure");
            }
            var retried = exploration.claimToWarehouse(node, warehouse);
            assertTrue(retried.success());
            assertEquals(5, warehouse.getContents(owner).get("DIAMOND"));
            drain(database);
        } finally {
            database.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            WarehouseService warehouse = new WarehouseService(plugin, restarted);
            warehouse.loadAllSync();
            assertEquals(5, warehouse.getContents(owner).get("DIAMOND"));
            try (var connection = restarted.getConnection();
                 Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT COUNT(*) FROM idle_exploration_events")) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        } finally {
            restarted.shutdown();
        }
    }

    /** The write queue is single-threaded, so an empty unit behind it is a barrier. */
    private void drain(Database database) {
        database.executeTransaction("drain", connection -> { });
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
