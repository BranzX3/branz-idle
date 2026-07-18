package dev.branzx.idlefarm.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Database {

    private final IdleFarmPlugin plugin;
    private HikariDataSource dataSource;
    /**
     * Single-threaded writer: preserves operation order (e.g. claim then
     * unclaim of the same chunk) while keeping every DB write off the main
     * thread. In-memory caches are the authority during runtime; the queue
     * is the durability path.
     */
    private ExecutorService writeQueue;

    public Database(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("mysql");
        HikariConfig hikariConfig = new HikariConfig();
        String host = cfg.getString("host", "localhost");
        int port = cfg.getInt("port", 3306);
        String database = cfg.getString("database", "idlefarm");
        boolean useSSL = cfg.getBoolean("useSSL", false);

        hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=utf8");
        hikariConfig.setUsername(cfg.getString("username", "root"));
        hikariConfig.setPassword(cfg.getString("password", ""));
        hikariConfig.setMaximumPoolSize(cfg.getInt("pool-size", 10));
        hikariConfig.setPoolName("IdleFarm-Pool");

        this.dataSource = new HikariDataSource(hikariConfig);
        this.writeQueue = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "IdleFarm-DB-Writer");
            thread.setDaemon(false);
            return thread;
        });
        createSchema();
    }

    /** Queue a DB write to run off the main thread, in submission order. */
    public void submitWrite(Runnable write) {
        writeQueue.submit(() -> {
            try {
                write.run();
            } catch (Exception e) {
                plugin.getLogger().severe("Queued DB write failed: " + e.getMessage());
            }
        });
    }

    private void createSchema() {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS idlefarm_players (
                    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    name VARCHAR(16) NOT NULL,
                    balance DOUBLE NOT NULL DEFAULT 0,
                    total_online_minutes BIGINT NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS idlefarm_nodes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    owner_uuid VARCHAR(36) NOT NULL,
                    world VARCHAR(64) NOT NULL,
                    chunk_x INT NOT NULL,
                    chunk_z INT NOT NULL,
                    node_type VARCHAR(32) NOT NULL,
                    tier INT NOT NULL DEFAULT 1,
                    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    exploration_level INT NOT NULL DEFAULT 0,
                    exploration_exp BIGINT NOT NULL DEFAULT 0,
                    last_tick_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    storage_json MEDIUMTEXT,
                    UNIQUE KEY uk_chunk (world, chunk_x, chunk_z),
                    KEY idx_owner (owner_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS idlefarm_node_cap (
                    owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    base_cap INT NOT NULL,
                    bonus_cap INT NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS idlefarm_trust (
                    owner_uuid VARCHAR(36) NOT NULL,
                    trusted_uuid VARCHAR(36) NOT NULL,
                    level VARCHAR(16) NOT NULL,
                    PRIMARY KEY (owner_uuid, trusted_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS idlefarm_workers (
                    worker_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                    rarity VARCHAR(16) NOT NULL,
                    trait VARCHAR(24) NOT NULL,
                    stats VARCHAR(64) NOT NULL,
                    name VARCHAR(32) NOT NULL,
                    skin VARCHAR(64) NOT NULL DEFAULT 'Steve',
                    level INT NOT NULL DEFAULT 1,
                    exp BIGINT NOT NULL DEFAULT 0,
                    assigned_node_id BIGINT NULL,
                    state VARCHAR(16) NOT NULL DEFAULT 'ITEM',
                    KEY idx_assigned (assigned_node_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS idlefarm_snapshots (
                    node_id BIGINT NOT NULL PRIMARY KEY,
                    blocks_json MEDIUMTEXT NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS idlefarm_audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    actor_uuid VARCHAR(36) NOT NULL,
                    action VARCHAR(32) NOT NULL,
                    detail_json TEXT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    KEY idx_actor (actor_uuid),
                    KEY idx_action (action)
                )
                """
        };

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                statement.executeUpdate(sql);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize MySQL schema: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void shutdown() {
        if (writeQueue != null) {
            writeQueue.shutdown();
            try {
                if (!writeQueue.awaitTermination(30, TimeUnit.SECONDS)) {
                    plugin.getLogger().severe("DB write queue did not drain within 30s; some writes may be lost.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
