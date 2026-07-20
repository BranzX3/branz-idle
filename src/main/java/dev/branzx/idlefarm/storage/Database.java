package dev.branzx.idlefarm.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Database {

    private final IdleFarmPlugin plugin;
    private HikariDataSource dataSource;
    private boolean sqlite;
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

    public boolean isSqlite() {
        return sqlite;
    }

    public void init() {
        String type = plugin.getConfig().getString("storage.type", "sqlite")
                .toLowerCase(Locale.ROOT);
        this.sqlite = !type.equals("mysql");

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("IdleFarm-Pool");

        if (sqlite) {
            File dbFile = new File(plugin.getDataFolder(), "idlefarm.db");
            plugin.getDataFolder().mkdirs();
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // SQLite is a single-writer engine; one connection avoids
            // SQLITE_BUSY contention entirely.
            hikariConfig.setMaximumPoolSize(1);
        } else {
            ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("mysql");
            String host = cfg.getString("host", "localhost");
            int port = cfg.getInt("port", 3306);
            String database = cfg.getString("database", "idlefarm");
            boolean useSSL = cfg.getBoolean("useSSL", false);
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=utf8");
            hikariConfig.setUsername(cfg.getString("username", "root"));
            hikariConfig.setPassword(cfg.getString("password", ""));
            hikariConfig.setMaximumPoolSize(cfg.getInt("pool-size", 10));
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        this.writeQueue = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "IdleFarm-DB-Writer");
            thread.setDaemon(false);
            return thread;
        });
        createSchema();
        plugin.getLogger().info("Storage: " + (sqlite ? "SQLite (idlefarm.db)" : "MySQL"));
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

    private static final String[] MYSQL_DDL = {
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
                id BIGINT NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                world VARCHAR(64) NOT NULL,
                chunk_x INT NOT NULL,
                chunk_z INT NOT NULL,
                node_type VARCHAR(32) NOT NULL,
                tier INT NOT NULL DEFAULT 1,
                state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                origin_y INT NOT NULL DEFAULT 0,
                upgrade_ends_at BIGINT NOT NULL DEFAULT 0,
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
                owner_uuid VARCHAR(36) NULL,
                rarity VARCHAR(16) NOT NULL,
                trait VARCHAR(24) NOT NULL,
                stats VARCHAR(64) NOT NULL,
                name VARCHAR(32) NOT NULL,
                skin VARCHAR(64) NOT NULL DEFAULT 'Steve',
                level INT NOT NULL DEFAULT 1,
                exp BIGINT NOT NULL DEFAULT 0,
                assigned_node_id BIGINT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'ITEM',
                KEY idx_assigned (assigned_node_id),
                KEY idx_owner_state (owner_uuid, state)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_warehouse (
                owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                capacity INT NOT NULL,
                content_json LONGTEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_snapshots (
                node_id BIGINT NOT NULL PRIMARY KEY,
                blocks_json MEDIUMTEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_perks (
                owner_uuid VARCHAR(36) NOT NULL,
                perk VARCHAR(32) NOT NULL,
                PRIMARY KEY (owner_uuid, perk)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_fuse_pity (
                owner_uuid VARCHAR(36) NOT NULL,
                rarity VARCHAR(16) NOT NULL,
                fails INT NOT NULL DEFAULT 0,
                PRIMARY KEY (owner_uuid, rarity)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_bag_cap (
                owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                bonus INT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_worker_anchors (
                worker_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                node_id BIGINT NOT NULL,
                spawn_x INT NOT NULL, spawn_y INT NOT NULL, spawn_z INT NOT NULL,
                work_x INT NOT NULL, work_y INT NOT NULL, work_z INT NOT NULL,
                KEY idx_wa_node (node_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_expedition (
                week VARCHAR(10) NOT NULL,
                owner_uuid VARCHAR(36) NOT NULL,
                contribution BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (week, owner_uuid)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_expedition_locks (
                worker_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                ends_at TIMESTAMP NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_boosters (
                owner_uuid VARCHAR(36) NOT NULL,
                booster_type VARCHAR(32) NOT NULL,
                multiplier DOUBLE NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                PRIMARY KEY (owner_uuid, booster_type)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_streaks (
                owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                current_streak INT NOT NULL DEFAULT 0,
                last_login_day VARCHAR(10) NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_exploration_events (
                id BIGINT NOT NULL PRIMARY KEY,
                node_id BIGINT NOT NULL,
                event_type VARCHAR(32) NOT NULL,
                state VARCHAR(16) NOT NULL,
                spawned_at TIMESTAMP NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                started_at TIMESTAMP NULL,
                ends_at TIMESTAMP NULL,
                worker_uuids VARCHAR(512),
                outcome_grade VARCHAR(16) NULL,
                loot VARCHAR(1024) NULL,
                KEY idx_node (node_id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_node_research (
                node_id BIGINT NOT NULL PRIMARY KEY,
                last_research_at BIGINT NOT NULL,
                research_day VARCHAR(10) NOT NULL,
                earned_today INT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_game_state (
                owner_uuid VARCHAR(36) NOT NULL,
                scope VARCHAR(24) NOT NULL,
                scope_id VARCHAR(64) NOT NULL,
                state_key VARCHAR(64) NOT NULL,
                value_text TEXT,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (owner_uuid, scope, scope_id, state_key)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_discoveries (
                owner_uuid VARCHAR(36) NOT NULL,
                node_type VARCHAR(32) NOT NULL,
                material VARCHAR(64) NOT NULL,
                lifetime_count BIGINT NOT NULL DEFAULT 0,
                first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (owner_uuid, node_type, material)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_resource_caps (
                owner_uuid VARCHAR(36) NOT NULL,
                material VARCHAR(64) NOT NULL,
                period_key VARCHAR(16) NOT NULL,
                amount INT NOT NULL DEFAULT 0,
                PRIMARY KEY (owner_uuid, material, period_key)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_credit_wallet (
                owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                credits BIGINT NOT NULL DEFAULT 0,
                season_id VARCHAR(32) NOT NULL DEFAULT 'preseason',
                season_coin_offset BIGINT NOT NULL DEFAULT 0,
                season_coins_earned BIGINT NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_credit_ledger (
                transaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                entry_type VARCHAR(24) NOT NULL,
                amount BIGINT NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                KEY idx_credit_owner (owner_uuid)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_trade_receipts (
                trade_id VARCHAR(64) NOT NULL PRIMARY KEY,
                player_a VARCHAR(36) NOT NULL,
                player_b VARCHAR(36) NOT NULL,
                offer_a TEXT,
                offer_b TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                KEY idx_trade_a (player_a),
                KEY idx_trade_b (player_b)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_telemetry (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                owner_uuid VARCHAR(36) NULL,
                event_type VARCHAR(48) NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                KEY idx_telemetry_event (event_type),
                KEY idx_telemetry_owner (owner_uuid)
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

    private static final String[] SQLITE_DDL = {
            """
            CREATE TABLE IF NOT EXISTS idlefarm_players (
                uuid TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                balance REAL NOT NULL DEFAULT 0,
                total_online_minutes INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_nodes (
                id INTEGER NOT NULL PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                world TEXT NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                node_type TEXT NOT NULL,
                tier INTEGER NOT NULL DEFAULT 1,
                state TEXT NOT NULL DEFAULT 'ACTIVE',
                origin_y INTEGER NOT NULL DEFAULT 0,
                upgrade_ends_at INTEGER NOT NULL DEFAULT 0,
                exploration_level INTEGER NOT NULL DEFAULT 0,
                exploration_exp INTEGER NOT NULL DEFAULT 0,
                last_tick_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                storage_json TEXT,
                UNIQUE (world, chunk_x, chunk_z)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_nodes_owner ON idlefarm_nodes (owner_uuid)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_node_cap (
                owner_uuid TEXT NOT NULL PRIMARY KEY,
                base_cap INTEGER NOT NULL,
                bonus_cap INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_trust (
                owner_uuid TEXT NOT NULL,
                trusted_uuid TEXT NOT NULL,
                level TEXT NOT NULL,
                PRIMARY KEY (owner_uuid, trusted_uuid)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_workers (
                worker_uuid TEXT NOT NULL PRIMARY KEY,
                owner_uuid TEXT NULL,
                rarity TEXT NOT NULL,
                trait TEXT NOT NULL,
                stats TEXT NOT NULL,
                name TEXT NOT NULL,
                skin TEXT NOT NULL DEFAULT 'Steve',
                level INTEGER NOT NULL DEFAULT 1,
                exp INTEGER NOT NULL DEFAULT 0,
                assigned_node_id INTEGER NULL,
                state TEXT NOT NULL DEFAULT 'ITEM'
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_workers_assigned ON idlefarm_workers (assigned_node_id)",
            "CREATE INDEX IF NOT EXISTS idx_workers_owner_state ON idlefarm_workers (owner_uuid, state)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_warehouse (
                owner_uuid TEXT NOT NULL PRIMARY KEY,
                capacity INTEGER NOT NULL,
                content_json TEXT
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_snapshots (
                node_id INTEGER NOT NULL PRIMARY KEY,
                blocks_json TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_perks (
                owner_uuid TEXT NOT NULL,
                perk TEXT NOT NULL,
                PRIMARY KEY (owner_uuid, perk)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_fuse_pity (
                owner_uuid TEXT NOT NULL,
                rarity TEXT NOT NULL,
                fails INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (owner_uuid, rarity)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_bag_cap (
                owner_uuid TEXT NOT NULL PRIMARY KEY,
                bonus INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_worker_anchors (
                worker_uuid TEXT NOT NULL PRIMARY KEY,
                node_id INTEGER NOT NULL,
                spawn_x INTEGER NOT NULL, spawn_y INTEGER NOT NULL, spawn_z INTEGER NOT NULL,
                work_x INTEGER NOT NULL, work_y INTEGER NOT NULL, work_z INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_wa_node ON idlefarm_worker_anchors (node_id)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_expedition (
                week TEXT NOT NULL,
                owner_uuid TEXT NOT NULL,
                contribution INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (week, owner_uuid)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_expedition_locks (
                worker_uuid TEXT NOT NULL PRIMARY KEY,
                ends_at TIMESTAMP NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_boosters (
                owner_uuid TEXT NOT NULL,
                booster_type TEXT NOT NULL,
                multiplier REAL NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                PRIMARY KEY (owner_uuid, booster_type)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_streaks (
                owner_uuid TEXT NOT NULL PRIMARY KEY,
                current_streak INTEGER NOT NULL DEFAULT 0,
                last_login_day TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_exploration_events (
                id INTEGER NOT NULL PRIMARY KEY,
                node_id INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                state TEXT NOT NULL,
                spawned_at TIMESTAMP NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                started_at TIMESTAMP NULL,
                ends_at TIMESTAMP NULL,
                worker_uuids TEXT,
                outcome_grade TEXT NULL,
                loot TEXT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_events_node ON idlefarm_exploration_events (node_id)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_node_research (
                node_id INTEGER NOT NULL PRIMARY KEY,
                last_research_at INTEGER NOT NULL,
                research_day TEXT NOT NULL,
                earned_today INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_game_state (
                owner_uuid TEXT NOT NULL,
                scope TEXT NOT NULL,
                scope_id TEXT NOT NULL,
                state_key TEXT NOT NULL,
                value_text TEXT,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (owner_uuid, scope, scope_id, state_key)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_discoveries (
                owner_uuid TEXT NOT NULL,
                node_type TEXT NOT NULL,
                material TEXT NOT NULL,
                lifetime_count INTEGER NOT NULL DEFAULT 0,
                first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (owner_uuid, node_type, material)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_resource_caps (
                owner_uuid TEXT NOT NULL,
                material TEXT NOT NULL,
                period_key TEXT NOT NULL,
                amount INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (owner_uuid, material, period_key)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_credit_wallet (
                owner_uuid TEXT NOT NULL PRIMARY KEY,
                credits INTEGER NOT NULL DEFAULT 0,
                season_id TEXT NOT NULL DEFAULT 'preseason',
                season_coin_offset INTEGER NOT NULL DEFAULT 0,
                season_coins_earned INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS idlefarm_credit_ledger (
                transaction_id TEXT NOT NULL PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                entry_type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_credit_owner ON idlefarm_credit_ledger (owner_uuid)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_trade_receipts (
                trade_id TEXT NOT NULL PRIMARY KEY,
                player_a TEXT NOT NULL,
                player_b TEXT NOT NULL,
                offer_a TEXT,
                offer_b TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_trade_a ON idlefarm_trade_receipts (player_a)",
            "CREATE INDEX IF NOT EXISTS idx_trade_b ON idlefarm_trade_receipts (player_b)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_telemetry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid TEXT NULL,
                event_type TEXT NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_telemetry_event ON idlefarm_telemetry (event_type)",
            "CREATE INDEX IF NOT EXISTS idx_telemetry_owner ON idlefarm_telemetry (owner_uuid)",
            """
            CREATE TABLE IF NOT EXISTS idlefarm_audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                actor_uuid TEXT NOT NULL,
                action TEXT NOT NULL,
                detail_json TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_audit_actor ON idlefarm_audit_log (actor_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_audit_action ON idlefarm_audit_log (action)"
    };

    private void createSchema() {
        String[] ddl = sqlite ? SQLITE_DDL : MYSQL_DDL;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                statement.executeUpdate(sql);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
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
