package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exploration Events: bracket-gated RNG runs. Events spawn randomly per
 * node (rolled only while the owner is online), expire if ignored, are
 * staffed by a worker team (state EXPLORING — NPCs walk off), complete on
 * real time (Speed-reduced), and pay guaranteed loot whose grade
 * (NORMAL/GREAT/JACKPOT) is shifted by team Luck. Completion grants the
 * node a large exploration-EXP bonus — the fast lane for brackets.
 */
public final class ExplorationService {

    public static final class EventRecord {
        final long id;
        final long nodeId;
        final String eventType;
        volatile String state; // AVAILABLE | RUNNING | COMPLETED
        final long spawnedAt;
        volatile long expiresAt;
        volatile long startedAt;
        volatile long endsAt;
        volatile String workerUuids = "";
        volatile String grade;
        volatile String loot;

        EventRecord(long id, long nodeId, String eventType, String state, long spawnedAt, long expiresAt) {
            this.id = id;
            this.nodeId = nodeId;
            this.eventType = eventType;
            this.state = state;
            this.spawnedAt = spawnedAt;
            this.expiresAt = expiresAt;
        }

        public long getId() {
            return id;
        }

        public String getEventType() {
            return eventType;
        }

        public String getState() {
            return state;
        }

        public String getGrade() {
            return grade;
        }

        public String getLoot() {
            return loot;
        }

        public long getEndsAt() {
            return endsAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final Map<Long, EventRecord> eventsByNode = new ConcurrentHashMap<>();
    private final AtomicLong nextEventId = new AtomicLong(1);
    private BukkitRunnable task;

    public ExplorationService(IdleFarmPlugin plugin, Database database,
                              NodeStore nodeStore, WorkerStore workerStore) {
        this.plugin = plugin;
        this.database = database;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT id, node_id, event_type, state, spawned_at, expires_at, started_at, ends_at, "
                             + "worker_uuids, outcome_grade, loot FROM idlefarm_exploration_events");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                EventRecord event = new EventRecord(
                        rs.getLong("id"),
                        rs.getLong("node_id"),
                        rs.getString("event_type"),
                        rs.getString("state"),
                        rs.getTimestamp("spawned_at").getTime(),
                        rs.getTimestamp("expires_at").getTime());
                if (rs.getTimestamp("started_at") != null) {
                    event.startedAt = rs.getTimestamp("started_at").getTime();
                }
                if (rs.getTimestamp("ends_at") != null) {
                    event.endsAt = rs.getTimestamp("ends_at").getTime();
                }
                event.workerUuids = rs.getString("worker_uuids") == null ? "" : rs.getString("worker_uuids");
                event.grade = rs.getString("outcome_grade");
                event.loot = rs.getString("loot");
                eventsByNode.put(event.nodeId, event);
                nextEventId.accumulateAndGet(event.id + 1, Math::max);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load exploration events: " + e.getMessage());
        }
    }

    public void start() {
        long interval = plugin.getConfig().getLong("exploration.check-interval-seconds", 30) * 20L;
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

    public EventRecord getEvent(long nodeId) {
        return eventsByNode.get(nodeId);
    }

    // ---- brackets ----

    public int bracket(NodeRecord node) {
        int size = plugin.getConfig().getInt("exploration.bracket-size", 10);
        return node.getExplorationLevel() / size + 1;
    }

    public long expForNextExplorationLevel(int level) {
        long base = plugin.getConfig().getLong("exploration.exp-per-level-base", 500);
        return base * (level + 1);
    }

    /** Grants exploration EXP to a node, applying level-ups. Caller persists. */
    public void grantExplorationExp(NodeRecord node, long amount) {
        int cap = plugin.getConfig().getInt("exploration.level-cap", 100);
        if (amount <= 0 || node.getExplorationLevel() >= cap) {
            return;
        }
        node.setExplorationExp(node.getExplorationExp() + amount);
        while (node.getExplorationLevel() < cap
                && node.getExplorationExp() >= expForNextExplorationLevel(node.getExplorationLevel())) {
            node.setExplorationExp(node.getExplorationExp()
                    - expForNextExplorationLevel(node.getExplorationLevel()));
            node.setExplorationLevel(node.getExplorationLevel() + 1);
        }
    }

    // ---- lifecycle tick ----

    private void tick() {
        long now = System.currentTimeMillis();
        for (NodeRecord node : nodeStore.getAll()) {
            if (!node.getType().isProduction()) {
                continue;
            }
            EventRecord event = eventsByNode.get(node.getId());
            if (event == null) {
                maybeSpawn(node, now);
                continue;
            }
            switch (event.state) {
                case "AVAILABLE" -> {
                    // Expiry only counts down while the owner is online (no
                    // FOMO for sleeping — spec §5b.2).
                    Player owner = Bukkit.getPlayer(node.getOwnerUuid());
                    if (owner == null) {
                        event.expiresAt = now + (event.expiresAt - event.spawnedAt);
                        persist(event);
                    } else if (now >= event.expiresAt) {
                        remove(event);
                        owner.sendMessage(Component.text("An exploration event at your "
                                + node.getType() + " node has expired.", NamedTextColor.GRAY));
                    }
                }
                case "RUNNING" -> {
                    if (now >= event.endsAt) {
                        complete(node, event);
                    }
                }
                default -> { } // COMPLETED waits for claim
            }
        }
    }

    private void maybeSpawn(NodeRecord node, long now) {
        Player owner = Bukkit.getPlayer(node.getOwnerUuid());
        if (owner == null) {
            return; // spawn rolls only while owner online
        }
        double chance = plugin.getConfig().getDouble("exploration.spawn-chance-per-check", 0.05);
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        String eventType = pickEventType(bracket(node));
        if (eventType == null) {
            return;
        }
        long expiryMinutes = eventConfig(eventType).getLong("expiry-minutes", 30);
        EventRecord event = new EventRecord(nextEventId.getAndIncrement(), node.getId(), eventType,
                "AVAILABLE", now, now + expiryMinutes * 60_000L);
        eventsByNode.put(node.getId(), event);
        insert(event);
        owner.sendMessage(Component.text()
                .append(Component.text("[Exploration] ", NamedTextColor.GOLD))
                .append(Component.text(eventName(eventType) + " discovered at your " + node.getType()
                        + " node! Expires in " + expiryMinutes + "m — /idle explore", NamedTextColor.YELLOW))
                .build());
    }

    // ---- admin controls ----

    /** All configured event ids (for admin pickers). */
    public List<String> eventTypes() {
        ConfigurationSection events = plugin.getConfig().getConfigurationSection("exploration.events");
        return events == null ? List.of() : List.copyOf(events.getKeys(false));
    }

    /** Force-spawn a specific (or random-eligible) event at a node. Admin. */
    public String adminSpawn(NodeRecord node, String eventType) {
        if (eventsByNode.containsKey(node.getId())) {
            return "This node already has an active event.";
        }
        if (eventType == null) {
            eventType = pickEventType(bracket(node));
            if (eventType == null) {
                return "No eligible event type for this node's bracket.";
            }
        } else if (!eventTypes().contains(eventType)) {
            return "Unknown event type: " + eventType;
        }
        long now = System.currentTimeMillis();
        long expiryMinutes = eventConfig(eventType).getLong("expiry-minutes", 30);
        EventRecord event = new EventRecord(nextEventId.getAndIncrement(), node.getId(), eventType,
                "AVAILABLE", now, now + expiryMinutes * 60_000L);
        eventsByNode.put(node.getId(), event);
        insert(event);
        return null;
    }

    /** Force-set a node's exploration level. Admin. Caller persists the node. */
    public void adminSetLevel(NodeRecord node, int level) {
        node.setExplorationLevel(Math.max(0, level));
        node.setExplorationExp(0);
    }

    private String pickEventType(int bracket) {
        ConfigurationSection events = plugin.getConfig().getConfigurationSection("exploration.events");
        if (events == null) {
            return null;
        }
        List<String> eligible = new ArrayList<>();
        for (String id : events.getKeys(false)) {
            if (events.getConfigurationSection(id).getInt("min-bracket", 1) <= bracket) {
                eligible.add(id);
            }
        }
        return eligible.isEmpty() ? null
                : eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    }

    // ---- player actions ----

    public String start(NodeRecord node, int teamSize) {
        EventRecord event = eventsByNode.get(node.getId());
        if (event == null || !"AVAILABLE".equals(event.state)) {
            return "No event is waiting at this node.";
        }
        List<WorkerRecord> idle = workerStore.getAssigned(node.getId()).stream()
                .filter(w -> !WorkerRecord.STATE_EXPLORING.equals(w.getState()))
                .toList();
        if (idle.isEmpty()) {
            return "No available workers at this node.";
        }
        List<WorkerRecord> team = idle.subList(0, Math.min(teamSize, idle.size()));

        int speedSum = team.stream().mapToInt(w -> w.getStats().speed()).sum();
        long baseMinutes = eventConfig(event.eventType).getLong("duration-minutes", 20);
        long duration = (long) (baseMinutes * 60_000L / (1 + speedSum / 100.0));

        long now = System.currentTimeMillis();
        event.state = "RUNNING";
        event.startedAt = now;
        event.endsAt = now + duration;
        StringBuilder uuids = new StringBuilder();
        for (WorkerRecord worker : team) {
            worker.setState(WorkerRecord.STATE_EXPLORING);
            workerStore.update(worker);
            if (uuids.length() > 0) {
                uuids.append(',');
            }
            uuids.append(worker.getWorkerUuid());
        }
        event.workerUuids = uuids.toString();
        persist(event);
        return null; // success
    }

    private void complete(NodeRecord node, EventRecord event) {
        List<WorkerRecord> team = teamOf(event);
        int luckSum = team.stream().mapToInt(w -> w.getStats().luck()).sum();
        event.grade = rollGrade(luckSum);
        event.loot = rollLoot(event.eventType, event.grade, team.size());
        event.state = "COMPLETED";
        for (WorkerRecord worker : team) {
            worker.setState(WorkerRecord.STATE_WORKING);
            workerStore.update(worker);
        }
        long expBonus = plugin.getConfig().getLong("exploration.event-exp-bonus", 2000);
        grantExplorationExp(node, expBonus);
        nodeStore.updateProduction(node);
        persist(event);

        Player owner = Bukkit.getPlayer(node.getOwnerUuid());
        if (owner != null) {
            owner.sendMessage(Component.text()
                    .append(Component.text("[Exploration] ", NamedTextColor.GOLD))
                    .append(Component.text(eventName(event.eventType) + " finished — " + event.grade
                            + "! Claim with /idle explore claim", NamedTextColor.GREEN))
                    .build());
        }
    }

    /** Returns loot map and clears the event; caller delivers the items. */
    public Map<String, Integer> claim(NodeRecord node) {
        EventRecord event = eventsByNode.get(node.getId());
        if (event == null || !"COMPLETED".equals(event.state)) {
            return null;
        }
        Map<String, Integer> loot = new ConcurrentHashMap<>();
        if (event.loot != null && !event.loot.isBlank()) {
            for (String entry : event.loot.split(";")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    loot.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
                }
            }
        }
        remove(event);
        return loot;
    }

    /** Cancels any event on unclaim/convert: workers return, no loot. */
    public void cancel(NodeRecord node) {
        EventRecord event = eventsByNode.get(node.getId());
        if (event == null) {
            return;
        }
        for (WorkerRecord worker : teamOf(event)) {
            if (WorkerRecord.STATE_EXPLORING.equals(worker.getState())) {
                worker.setState(WorkerRecord.STATE_WORKING);
                workerStore.update(worker);
            }
        }
        remove(event);
    }

    // ---- rolls ----

    private String rollGrade(int luckSum) {
        double jackpot = plugin.getConfig().getDouble("exploration.grade-odds.jackpot-base", 5)
                + luckSum * plugin.getConfig().getDouble("exploration.grade-odds.jackpot-per-luck", 0.1);
        double great = plugin.getConfig().getDouble("exploration.grade-odds.great-base", 20)
                + luckSum * plugin.getConfig().getDouble("exploration.grade-odds.great-per-luck", 0.2);
        double roll = ThreadLocalRandom.current().nextDouble() * 100;
        if (roll < jackpot) {
            return "JACKPOT";
        }
        if (roll < jackpot + great) {
            return "GREAT";
        }
        return "NORMAL";
    }

    private String rollLoot(String eventType, String grade, int teamSize) {
        ConfigurationSection config = eventConfig(eventType);
        ConfigurationSection table = config.getConfigurationSection("loot");
        int baseCount = config.getInt("loot-count-base", 8);
        double gradeMultiplier = switch (grade) {
            case "JACKPOT" -> plugin.getConfig().getDouble("exploration.grade-multiplier.jackpot", 3.0);
            case "GREAT" -> plugin.getConfig().getDouble("exploration.grade-multiplier.great", 1.8);
            default -> 1.0;
        };
        int count = (int) Math.round(baseCount * gradeMultiplier * (1 + (teamSize - 1) * 0.5));

        Map<String, Integer> rolled = new ConcurrentHashMap<>();
        if (table != null) {
            List<String> materials = List.copyOf(table.getKeys(false));
            double totalWeight = materials.stream().mapToDouble(table::getDouble).sum();
            for (int i = 0; i < count; i++) {
                double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
                double cumulative = 0;
                for (String material : materials) {
                    cumulative += table.getDouble(material);
                    if (roll < cumulative) {
                        rolled.merge(material.toUpperCase(Locale.ROOT), 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : rolled.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    // ---- helpers / persistence ----

    private List<WorkerRecord> teamOf(EventRecord event) {
        List<WorkerRecord> team = new ArrayList<>();
        for (String uuid : event.workerUuids.split(",")) {
            if (!uuid.isBlank()) {
                WorkerRecord worker = workerStore.get(UUID.fromString(uuid));
                if (worker != null) {
                    team.add(worker);
                }
            }
        }
        return team;
    }

    public String eventName(String eventType) {
        return eventConfig(eventType).getString("name", eventType);
    }

    private ConfigurationSection eventConfig(String eventType) {
        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("exploration.events." + eventType);
        return section != null ? section
                : plugin.getConfig().createSection("exploration.events." + eventType);
    }

    private void insert(EventRecord event) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idlefarm_exploration_events (id, node_id, event_type, state, spawned_at, expires_at) "
                                 + "VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, event.id);
                insert.setLong(2, event.nodeId);
                insert.setString(3, event.eventType);
                insert.setString(4, event.state);
                insert.setTimestamp(5, new java.sql.Timestamp(event.spawnedAt));
                insert.setTimestamp(6, new java.sql.Timestamp(event.expiresAt));
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to insert exploration event: " + e.getMessage());
            }
        });
    }

    private void persist(EventRecord event) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE idlefarm_exploration_events SET state = ?, expires_at = ?, started_at = ?, "
                                 + "ends_at = ?, worker_uuids = ?, outcome_grade = ?, loot = ? WHERE id = ?")) {
                update.setString(1, event.state);
                update.setTimestamp(2, new java.sql.Timestamp(event.expiresAt));
                update.setTimestamp(3, event.startedAt == 0 ? null : new java.sql.Timestamp(event.startedAt));
                update.setTimestamp(4, event.endsAt == 0 ? null : new java.sql.Timestamp(event.endsAt));
                update.setString(5, event.workerUuids);
                update.setString(6, event.grade);
                update.setString(7, event.loot);
                update.setLong(8, event.id);
                update.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to update exploration event: " + e.getMessage());
            }
        });
    }

    private void remove(EventRecord event) {
        eventsByNode.remove(event.nodeId);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idlefarm_exploration_events WHERE id = ?")) {
                delete.setLong(1, event.id);
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete exploration event: " + e.getMessage());
            }
        });
    }
}
