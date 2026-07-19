package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Append-only action log for dupe/complaint investigation. Every Money
 * spend, gacha/fuse roll, claim change, and admin edit lands here through
 * the ordered write queue.
 */
public final class AuditService {

    public record Entry(String actor, String action, String detail, String createdAt) {
    }

    private final IdleFarmPlugin plugin;
    private final Database database;

    public AuditService(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void log(UUID actor, String action, String detail) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idlefarm_audit_log (actor_uuid, action, detail_json) VALUES (?, ?, ?)")) {
                insert.setString(1, actor.toString());
                insert.setString(2, action);
                insert.setString(3, detail);
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to write audit log: " + e.getMessage());
            }
        });
    }

    /** Blocking read — call off the main thread. Filter may be null. */
    public List<Entry> recentSync(UUID actorFilter, int limit) {
        List<Entry> entries = new ArrayList<>();
        String sql = actorFilter == null
                ? "SELECT actor_uuid, action, detail_json, created_at FROM idlefarm_audit_log "
                        + "ORDER BY id DESC LIMIT ?"
                : "SELECT actor_uuid, action, detail_json, created_at FROM idlefarm_audit_log "
                        + "WHERE actor_uuid = ? ORDER BY id DESC LIMIT ?";
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            int index = 1;
            if (actorFilter != null) {
                select.setString(index++, actorFilter.toString());
            }
            select.setInt(index, limit);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(
                            rs.getString("actor_uuid"),
                            rs.getString("action"),
                            rs.getString("detail_json"),
                            String.valueOf(rs.getTimestamp("created_at"))));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read audit log: " + e.getMessage());
        }
        return entries;
    }
}
