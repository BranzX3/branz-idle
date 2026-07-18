package dev.branzx.idlefarm.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.branzx.idlefarm.IdleFarmPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {

    private final IdleFarmPlugin plugin;
    private HikariDataSource dataSource;

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
        createSchema();
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
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
