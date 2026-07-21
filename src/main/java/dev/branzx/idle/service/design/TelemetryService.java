package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Append-only gameplay telemetry used by live-ops dashboards. */
public final class TelemetryService {

    public enum Severity { INFO, WARNING, CRITICAL }

    public record AdminAlert(Severity severity, String code, String message) {
    }

    public record EconomyDashboard(long players, double totalCoins, double richestBalance,
                                   long productionNodes, long warehouseCapacity,
                                   long itemsProduced7d, long itemsSunk7d,
                                   double sinkRatio, Map<String, Long> events,
                                   List<AdminAlert> alerts) {
    }

    private static final Pattern AMOUNT =
            Pattern.compile("\"amount\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    private final IdlePlugin plugin;
    private final Database database;

    public TelemetryService(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void record(UUID owner, String event, String detail) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idle_telemetry (owner_uuid, event_type, detail_json) VALUES (?, ?, ?)")) {
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
                     "SELECT event_type, COUNT(*) AS total FROM idle_telemetry "
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

    /** Portable SQLite/MySQL economy snapshot plus threshold-based alerts. */
    public EconomyDashboard economyDashboardSync() {
        long players = 0;
        double totalCoins = 0;
        double richest = 0;
        long nodes = 0;
        long capacity = 0;
        long produced = 0;
        long sunk = 0;
        Map<String, Long> events = summarySync();
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COUNT(*) AS players, COALESCE(SUM(balance), 0) AS total, "
                            + "COALESCE(MAX(balance), 0) AS richest FROM idle_players");
                 ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    players = rs.getLong("players");
                    totalCoins = rs.getDouble("total");
                    richest = rs.getDouble("richest");
                }
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COUNT(*) AS total FROM idle_nodes WHERE node_type <> 'RESIDENTIAL'");
                 ResultSet rs = query.executeQuery()) {
                if (rs.next()) nodes = rs.getLong("total");
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COALESCE(SUM(capacity), 0) AS total FROM idle_warehouse");
                 ResultSet rs = query.executeQuery()) {
                if (rs.next()) capacity = rs.getLong("total");
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT event_type, detail_json FROM idle_telemetry "
                            + "WHERE created_at >= ? AND event_type IN "
                            + "('ITEM_PRODUCED','PROJECT_CONTRIBUTED','SERVER_PROJECT_CONTRIBUTED')");
                 ) {
                query.setTimestamp(1, java.sql.Timestamp.from(Instant.now().minusSeconds(7L * 86_400)));
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        long amount = amount(rs.getString("detail_json"));
                        if ("ITEM_PRODUCED".equals(rs.getString("event_type"))) produced += amount;
                        else sunk += amount;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read economy dashboard: " + e.getMessage());
        }
        double ratio = produced <= 0 ? 1.0 : (double) sunk / produced;
        List<AdminAlert> alerts = buildAlerts(players, totalCoins, richest, produced, ratio, events);
        return new EconomyDashboard(players, totalCoins, richest, nodes, capacity,
                produced, sunk, ratio, Map.copyOf(events), List.copyOf(alerts));
    }

    private List<AdminAlert> buildAlerts(long players, double totalCoins, double richest, long produced,
                                         double sinkRatio, Map<String, Long> events) {
        List<AdminAlert> alerts = new ArrayList<>();
        double totalLimit = plugin.getConfig().getDouble("economy.alerts.max-total-coins", 1_000_000_000);
        double playerLimit = plugin.getConfig().getDouble("economy.alerts.max-player-balance", 10_000_000);
        double minimumSinkRatio = plugin.getConfig().getDouble("economy.alerts.min-material-sink-ratio", 0.10);
        long capHitLimit = plugin.getConfig().getLong("economy.alerts.max-rare-cap-hits-7d", 100);
        long minimumCommits =
                plugin.getConfig().getLong("economy.alerts.min-expedition-commits-7d", 3);
        long minimumObjectives =
                plugin.getConfig().getLong("economy.alerts.min-season-objective-completions-7d", 2);
        long claimFailureLimit =
                plugin.getConfig().getLong("economy.alerts.max-claim-failures-7d", 10);
        long catchUpLimit =
                plugin.getConfig().getLong("economy.alerts.max-catch-up-claims-7d", 250);
        if (totalCoins > totalLimit) {
            alerts.add(new AdminAlert(Severity.CRITICAL, "COIN_SUPPLY",
                    "Total Coins " + Math.round(totalCoins) + " exceed " + Math.round(totalLimit)));
        }
        if (richest > playerLimit) {
            alerts.add(new AdminAlert(Severity.WARNING, "PLAYER_BALANCE",
                    "Richest balance " + Math.round(richest) + " exceeds " + Math.round(playerLimit)));
        }
        if (produced > 0 && sinkRatio < minimumSinkRatio) {
            alerts.add(new AdminAlert(Severity.WARNING, "LOW_SINK_RATIO",
                    String.format("7d material sink ratio %.1f%% is below %.1f%%",
                            sinkRatio * 100, minimumSinkRatio * 100)));
        }
        if (events.getOrDefault("RARE_CAP_HIT", 0L) > capHitLimit) {
            alerts.add(new AdminAlert(Severity.WARNING, "RARE_CAP_PRESSURE",
                    "Rare caps were hit " + events.get("RARE_CAP_HIT") + " times in 7d"));
        }
        if (players > 0 && events.getOrDefault("EXPEDITION_COMMIT", 0L) < minimumCommits) {
            alerts.add(new AdminAlert(Severity.WARNING, "LOW_EXPEDITION_PARTICIPATION",
                    "Only " + events.getOrDefault("EXPEDITION_COMMIT", 0L)
                            + " Global Expedition commits were recorded in 7d"));
        }
        if (players > 0 && events.getOrDefault("SEASON_OBJECTIVE_COMPLETED", 0L)
                < minimumObjectives) {
            alerts.add(new AdminAlert(Severity.WARNING, "LOW_SEASON_PROGRESS",
                    "Only " + events.getOrDefault("SEASON_OBJECTIVE_COMPLETED", 0L)
                            + " seasonal objectives completed in 7d"));
        }
        long failures = events.getOrDefault("WAREHOUSE_CLAIM_FAILURE", 0L)
                + events.getOrDefault("EVENT_CLAIM_FAILURE", 0L)
                + events.getOrDefault("SETTLEMENT_RETRY", 0L);
        if (failures > claimFailureLimit) {
            alerts.add(new AdminAlert(Severity.CRITICAL, "CLAIM_FAILURES",
                    failures + " claim/settlement failures were recorded in 7d"));
        }
        if (events.getOrDefault("CATCH_UP_COMMISSION_CLAIMED", 0L) > catchUpLimit) {
            alerts.add(new AdminAlert(Severity.WARNING, "CATCH_UP_PRESSURE",
                    events.get("CATCH_UP_COMMISSION_CLAIMED")
                            + " catch-up commissions were claimed in 7d"));
        }
        if (alerts.isEmpty()) {
            alerts.add(new AdminAlert(Severity.INFO, "HEALTHY",
                    "No economy threshold is currently breached"));
        }
        return alerts;
    }

    private long amount(String json) {
        if (json == null) return 0;
        Matcher matcher = AMOUNT.matcher(json);
        if (!matcher.find()) return 0;
        try {
            return Math.max(0, Math.round(Double.parseDouble(matcher.group(1))));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
