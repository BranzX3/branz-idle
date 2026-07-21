package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Weekly server-wide expedition (spec §8b). Players commit a node's idle
 * workers for a timed run; contribution (Σ worker stats) accrues to the
 * week's leaderboard. Committed workers are EXPLORING (separate lock table,
 * so a node's regular exploration event can run alongside). At week
 * rollover the previous week settles: top ranks get Money, results are
 * broadcast, and a fresh week begins automatically.
 */
public final class GlobalExpeditionService {

    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Bangkok");

    public record Score(UUID owner, long contribution) {
    }

    public record ParticipationBand(String id, String name, long threshold, double coins) {
    }

    private final IdlePlugin plugin;
    private final Database database;
    private final WorkerStore workerStore;
    private final PlayerDataStore dataStore;
    private final GameDesignService design;
    // week -> owner -> contribution (only current week is hot; past weeks settle away)
    private final Map<String, Map<UUID, Long>> scores = new ConcurrentHashMap<>();
    private final Map<UUID, Long> workerLocks = new ConcurrentHashMap<>(); // worker -> endsAt
    private volatile String activeWeek;
    private BukkitRunnable task;

    public GlobalExpeditionService(IdlePlugin plugin, Database database,
                                   WorkerStore workerStore, PlayerDataStore dataStore,
                                   GameDesignService design) {
        this.plugin = plugin;
        this.database = database;
        this.workerStore = workerStore;
        this.dataStore = dataStore;
        this.design = design;
    }

    public static String currentWeek() {
        LocalDate now = LocalDate.now(GAME_ZONE);
        WeekFields iso = WeekFields.ISO;
        return now.get(iso.weekBasedYear()) + "-W" + String.format("%02d", now.get(iso.weekOfWeekBasedYear()));
    }

    public String activeWeek() {
        return activeWeek;
    }

    public void loadAllSync() {
        this.activeWeek = currentWeek();
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT week, owner_uuid, contribution FROM idle_expedition");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    scores.computeIfAbsent(rs.getString("week"), k -> new ConcurrentHashMap<>())
                            .put(UUID.fromString(rs.getString("owner_uuid")), rs.getLong("contribution"));
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT worker_uuid, ends_at FROM idle_expedition_locks");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    UUID workerUuid = UUID.fromString(rs.getString("worker_uuid"));
                    workerLocks.put(workerUuid, rs.getTimestamp("ends_at").getTime());
                    migrateLegacyWorkerState(connection, workerUuid);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load expedition data: " + e.getMessage());
        }
        // A server may have been offline during rollover. Settle every stale
        // week on startup instead of leaving its rewards orphaned forever.
        List.copyOf(scores.keySet()).stream()
                .filter(week -> !week.equals(activeWeek))
                .sorted()
                .forEach(this::settle);
    }

    /**
     * Older releases represented a global commitment by overwriting the
     * worker's shared state with EXPLORING. Clear that legacy state only when
     * the worker is not part of a persisted regular exploration event.
     */
    private void migrateLegacyWorkerState(Connection connection, UUID workerUuid) throws SQLException {
        WorkerRecord worker = workerStore.get(workerUuid);
        if (worker == null || !WorkerRecord.STATE_EXPLORING.equals(worker.getState())) {
            return;
        }
        boolean regular;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT 1 FROM idle_exploration_events "
                        + "WHERE state = 'RUNNING' AND worker_uuids LIKE ?")) {
            select.setString(1, "%" + workerUuid + "%");
            try (ResultSet rs = select.executeQuery()) {
                regular = rs.next();
            }
        }
        if (!regular) {
            worker.setState(WorkerRecord.STATE_WORKING);
            workerStore.update(worker);
        }
    }

    public void start() {
        long interval = 60L * 20L;
        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        this.task.runTaskTimer(plugin, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    // ---- player actions ----

    public long contributionOf(UUID owner) {
        return scores.getOrDefault(activeWeek, Map.of()).getOrDefault(owner, 0L);
    }

    public List<Score> top(int limit) {
        List<Score> list = new ArrayList<>();
        scores.getOrDefault(activeWeek, Map.of())
                .forEach((owner, value) -> list.add(new Score(owner, value)));
        list.sort(Comparator.comparingLong(Score::contribution).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    public long commitDurationMinutes() {
        return plugin.getConfig().getLong("expedition.commit-duration-minutes", 60);
    }

    public List<ParticipationBand> participationBands() {
        List<ParticipationBand> bands = new ArrayList<>();
        for (Map<?, ?> row : plugin.getConfig().getMapList("expedition.participation-bands")) {
            Object idValue = row.get("id");
            String id = idValue == null ? "" : String.valueOf(idValue).trim();
            Object nameValue = row.get("name");
            String name = nameValue == null ? id : String.valueOf(nameValue).trim();
            long threshold = number(row.get("threshold"), 0).longValue();
            double coins = number(row.get("coins"), 0).doubleValue();
            if (!id.isBlank() && threshold > 0 && coins >= 0) {
                bands.add(new ParticipationBand(id, name, threshold, coins));
            }
        }
        if (bands.isEmpty()) {
            bands.add(new ParticipationBand("participant", "Participant",
                    plugin.getConfig().getLong("expedition.participation-threshold", 250),
                    plugin.getConfig().getDouble("expedition.participation-reward", 500)));
        }
        bands.sort(Comparator.comparingLong(ParticipationBand::threshold));
        return List.copyOf(bands);
    }

    public ParticipationBand nextBand(UUID owner) {
        long contribution = contributionOf(owner);
        return participationBands().stream()
                .filter(band -> band.threshold() > contribution).findFirst().orElse(null);
    }

    public boolean isCommitted(UUID workerUuid) {
        Long endsAt = workerLocks.get(workerUuid);
        return endsAt != null && endsAt > System.currentTimeMillis();
    }

    public boolean hasCommitments(long nodeId) {
        return workerStore.getAssigned(nodeId).stream()
                .anyMatch(worker -> isCommitted(worker.getWorkerUuid()));
    }

    /**
     * Commits all workers not already committed to the Global Expedition.
     * This lock is independent from regular exploration state, so the two
     * expedition systems can coexist as specified.
     * Returns an error message or null on success.
     */
    public String commit(UUID owner, NodeRecord node) {
        // Delivery already filters; ownership stays authoritative here.
        if (node == null || !node.getOwnerUuid().equals(owner)) {
            return "You do not own this node.";
        }
        List<WorkerRecord> idle = workerStore.getAssigned(node.getId()).stream()
                .filter(worker -> !isCommitted(worker.getWorkerUuid()))
                .toList();
        if (idle.isEmpty()) {
            return "No available workers at this node.";
        }
        long endsAt = System.currentTimeMillis() + commitDurationMinutes() * 60_000L;
        long gained = 0;
        java.util.Set<String> roles = new java.util.HashSet<>();
        for (WorkerRecord worker : idle) {
            var stats = worker.getStats();
            gained += stats.diligence() + stats.luck() + stats.stamina() + stats.speed()
                    + (long) worker.getLevel() * 2;
            int max = Math.max(Math.max(stats.diligence(), stats.luck()),
                    Math.max(stats.stamina(), stats.speed()));
            roles.add(max == stats.diligence() ? "producer" : max == stats.luck() ? "scout"
                    : max == stats.stamina() ? "researcher" : "runner");
        }
        if (roles.size() >= 3) {
            gained = Math.round(gained * 1.10);
        }
        long existing = contributionOf(owner);
        long cap = plugin.getConfig().getLong("expedition.weekly-contribution-cap", 10_000);
        // Diminishing returns preserve participation without making repeated
        // 24/7 commits the only viable strategy.
        double diminishing = 1.0 / (1.0 + existing / Math.max(1.0, cap / 2.0));
        gained = Math.min(Math.max(0, cap - existing), Math.max(1, Math.round(gained * diminishing)));
        if (gained <= 0) {
            return "Weekly contribution cap reached.";
        }
        for (WorkerRecord worker : idle) {
            workerLocks.put(worker.getWorkerUuid(), endsAt);
            persistLock(worker.getWorkerUuid(), endsAt);
        }
        addContribution(owner, gained);
        design.onGlobalExpeditionCommitted(owner);
        design.telemetry(owner, "EXPEDITION_COMMIT",
                "{\"week\":\"" + activeWeek + "\",\"amount\":" + gained
                        + ",\"contribution\":" + (existing + gained) + "}");
        return null;
    }

    private void addContribution(UUID owner, long gained) {
        long updated = scores.computeIfAbsent(activeWeek, k -> new ConcurrentHashMap<>())
                .merge(owner, gained, Long::sum);
        String week = activeWeek;
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idle_expedition (week, owner_uuid, contribution) VALUES (?, ?, ?)")) {
                upsert.setString(1, week);
                upsert.setString(2, owner.toString());
                upsert.setLong(3, updated);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist expedition score: " + e.getMessage());
            }
        });
    }

    // ---- lifecycle ----

    private void tick() {
        long now = System.currentTimeMillis();
        // Release expired worker locks.
        for (var entry : List.copyOf(workerLocks.entrySet())) {
            if (entry.getValue() <= now) {
                workerLocks.remove(entry.getKey());
                removeLock(entry.getKey());
            }
        }
        // Week rollover: settle the finished week.
        String week = currentWeek();
        if (!week.equals(activeWeek)) {
            settle(activeWeek);
            activeWeek = week;
        }
    }

    private void settle(String week) {
        Map<UUID, Long> finished = scores.get(week);
        if (finished == null || finished.isEmpty()) {
            return;
        }
        List<Score> ranking = new ArrayList<>();
        finished.forEach((owner, value) -> ranking.add(new Score(owner, value)));
        ranking.sort(Comparator.comparingLong(Score::contribution).reversed());

        List<Double> rewards = plugin.getConfig().getDoubleList("expedition.rewards");
        if (rewards.isEmpty()) {
            rewards = List.of(5000.0, 3000.0, 1500.0);
        }
        StringBuilder announce = new StringBuilder("Global Expedition " + week + " results: ");
        Map<UUID, Double> payouts = new LinkedHashMap<>();
        for (Score score : ranking) {
            if (design.featureEnabled("expedition-bands", score.owner())) {
                for (ParticipationBand band : participationBands()) {
                    if (score.contribution() >= band.threshold()) {
                        payouts.merge(score.owner(), band.coins(), Double::sum);
                    }
                }
            } else {
                long threshold = plugin.getConfig().getLong("expedition.participation-threshold", 250);
                double reward = plugin.getConfig().getDouble("expedition.participation-reward", 500);
                if (score.contribution() >= threshold) {
                    payouts.merge(score.owner(), reward, Double::sum);
                }
            }
        }
        for (int i = 0; i < ranking.size() && i < rewards.size(); i++) {
            Score score = ranking.get(i);
            double reward = rewards.get(i);
            payouts.merge(score.owner(), reward, Double::sum);
            var offline = Bukkit.getOfflinePlayer(score.owner());
            announce.append("#").append(i + 1).append(" ")
                    .append(offline.getName() == null ? "?" : offline.getName())
                    .append(" (").append(score.contribution()).append(") ");
        }
        boolean committed = database.executeTransaction("settle Global Expedition " + week, connection -> {
            for (Map.Entry<UUID, Double> payout : payouts.entrySet()) {
                try (PreparedStatement ledger = connection.prepareStatement(
                        "INSERT INTO idle_reward_settlements "
                                + "(settlement_id, owner_uuid, amount) VALUES (?, ?, ?)")) {
                    ledger.setString(1, "GLOBAL_EXPEDITION:" + week + ":" + payout.getKey());
                    ledger.setString(2, payout.getKey().toString());
                    ledger.setDouble(3, payout.getValue());
                    ledger.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE idle_players SET balance = balance + ? WHERE uuid = ?")) {
                    update.setDouble(1, payout.getValue());
                    update.setString(2, payout.getKey().toString());
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Missing player row for " + payout.getKey());
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM idle_expedition WHERE week = ?")) {
                delete.setString(1, week);
                delete.executeUpdate();
            }
        });
        if (!committed) {
            plugin.getLogger().severe("Global Expedition " + week
                    + " was not settled; it will retry after restart/rollover.");
            return;
        }
        payouts.forEach((owner, amount) -> {
            PlayerData online = dataStore.getOnline(owner);
            if (online != null) online.addBalance(amount);
            long contribution = finished.getOrDefault(owner, 0L);
            for (ParticipationBand band : participationBands()) {
                if (contribution >= band.threshold()) {
                    design.telemetry(owner, "EXPEDITION_BAND_REACHED",
                            "{\"week\":\"" + week + "\",\"band\":\"" + band.id()
                                    + "\",\"threshold\":" + band.threshold() + "}");
                }
            }
            design.telemetry(owner, "EXPEDITION_WEEK_SETTLED",
                    "{\"week\":\"" + week + "\",\"amount\":" + Math.round(amount) + "}");
        });
        scores.remove(week);
        Bukkit.broadcast(Component.text("[Expedition] " + announce + " ", NamedTextColor.GOLD)
                .append(dev.branzx.idle.command.CommandLinks.run("[Open]", "/idle expedition")));
    }

    private Number number(Object value, Number fallback) {
        if (value instanceof Number number) return number;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void persistLock(UUID worker, long endsAt) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idle_expedition_locks (worker_uuid, ends_at) VALUES (?, ?)")) {
                upsert.setString(1, worker.toString());
                upsert.setTimestamp(2, new Timestamp(endsAt));
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist expedition lock: " + e.getMessage());
            }
        });
    }

    private void removeLock(UUID worker) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idle_expedition_locks WHERE worker_uuid = ?")) {
                delete.setString(1, worker.toString());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to remove expedition lock: " + e.getMessage());
            }
        });
    }
}
