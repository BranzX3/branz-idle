package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Append-only gameplay telemetry used by live-ops dashboards. */
public final class TelemetryService {

    private final IdleFarmPlugin plugin;
    private final Database database;

    public TelemetryService(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void record(UUID owner, String event, String detail) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idlefarm_telemetry (owner_uuid, event_type, detail_json) VALUES (?, ?, ?)")) {
                if (owner == null) insert.setNull(1, java.sql.Types.VARCHAR);
                else insert.setString(1, owner.toString());
                insert.setString(2, event);
                insert.setString(3, detail);
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to write telemetry: " + e.getMessage());
            }
        });
    }

    /** Seven-day event counts, most frequent first. Blocking; admin surfaces only. */
    public Map<String, Long> summarySync() {
        Map<String, Long> summary = new LinkedHashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT event_type, COUNT(*) AS total FROM idlefarm_telemetry "
                             + "WHERE created_at >= ? GROUP BY event_type ORDER BY total DESC")) {
            select.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(7L * 86_400)));
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) summary.put(rs.getString("event_type"), rs.getLong("total"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read telemetry summary: " + e.getMessage());
        }
        return summary;
    }
}
