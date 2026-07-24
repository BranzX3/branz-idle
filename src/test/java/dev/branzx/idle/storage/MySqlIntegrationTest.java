package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.service.WarehouseService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in compatibility gate against a real MySQL instance.
 *
 * <p>Set {@code IDLE_TEST_MYSQL=true} plus the optional
 * {@code IDLE_MYSQL_*} connection variables before running Gradle.</p>
 */
@EnabledIfEnvironmentVariable(named = "IDLE_TEST_MYSQL", matches = "(?i)true")
class MySqlIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAndCrossAggregateRowsSurviveRestart() throws Exception {
        IdlePlugin plugin = mysqlPlugin();
        UUID owner = UUID.randomUUID();
        Database database = new Database(plugin);
        database.init();
        try {
            WarehouseService warehouse = new WarehouseService(plugin, database);
            GameStateStore state = new GameStateStore(plugin, database);
            warehouse.deposit(owner, "STONE", 96);
            state.put(owner, "PROJECT", "mysql_probe", "progress", "48");
        } finally {
            database.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            WarehouseService warehouse = new WarehouseService(plugin, restarted);
            warehouse.loadAllSync();
            GameStateStore state = new GameStateStore(plugin, restarted);
            state.loadAllSync();
            assertEquals(96, warehouse.getContents(owner).get("STONE"));
            assertEquals("48", state.get(owner, "PROJECT", "mysql_probe", "progress"));
            cleanup(restarted, owner);
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void transactionFailureRollsBackEveryWrittenAggregate() throws Exception {
        IdlePlugin plugin = mysqlPlugin();
        UUID owner = UUID.randomUUID();
        Database database = new Database(plugin);
        database.init();
        try {
            WarehouseService warehouse = new WarehouseService(plugin, database);
            // The warehouse is persisted as the difference between two runtime
            // snapshots, so the probe writes "nothing -> 64 oak logs".
            WarehouseService.Snapshot before =
                    new WarehouseService.Snapshot(owner, 2_000, "");
            WarehouseService.Snapshot snapshot =
                    new WarehouseService.Snapshot(owner, 2_000, "OAK_LOG:64");
            GameStateStore.Row row =
                    new GameStateStore.Row(owner, "PROJECT", "rollback_probe", "progress", "64");

            boolean committed = database.executeTransaction("mysql rollback probe", connection -> {
                warehouse.write(connection, before, snapshot);
                GameStateStore.write(connection, row);
                throw new SQLException("injected rollback");
            });
            assertFalse(committed);

            warehouse.loadAllSync();
            GameStateStore state = new GameStateStore(plugin, database);
            state.loadAllSync();
            assertEquals(0, warehouse.getContents(owner).size());
            assertNull(state.get(owner, "PROJECT", "rollback_probe", "progress"));
        } finally {
            cleanup(database, owner);
            database.shutdown();
        }
    }

    private IdlePlugin mysqlPlugin() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("storage.type", "mysql");
        config.set("mysql.host", env("IDLE_MYSQL_HOST", "127.0.0.1"));
        config.set("mysql.port", Integer.parseInt(env("IDLE_MYSQL_PORT", "3306")));
        config.set("mysql.database", env("IDLE_MYSQL_DATABASE", "idle_test"));
        config.set("mysql.username", env("IDLE_MYSQL_USERNAME", "root"));
        config.set("mysql.password", env("IDLE_MYSQL_PASSWORD", ""));
        config.set("mysql.useSSL", false);
        config.set("mysql.allow-public-key-retrieval",
                Boolean.parseBoolean(env("IDLE_MYSQL_ALLOW_PUBLIC_KEY_RETRIEVAL", "false")));
        config.set("mysql.pool-size", 2);
        config.set("warehouse.base-capacity", 2000);
        IdlePlugin plugin = mock(IdlePlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("MySqlIntegrationTest"));
        return plugin;
    }

    private void cleanup(Database database, UUID owner) throws Exception {
        database.executeTransaction("mysql test cleanup", connection -> {
            try (var warehouse = connection.prepareStatement(
                    "DELETE FROM idle_warehouse WHERE owner_uuid = ?");
                 var state = connection.prepareStatement(
                         "DELETE FROM idle_game_state WHERE owner_uuid = ?")) {
                warehouse.setString(1, owner.toString());
                warehouse.executeUpdate();
                state.setString(1, owner.toString());
                state.executeUpdate();
            }
        });
    }

    private String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
