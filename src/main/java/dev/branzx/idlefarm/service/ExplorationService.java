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
import java.time.LocalDate;
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

    public record WarehouseClaimResult(boolean success, int total, String message) {
    }

    public record TeamPreview(int workers, long durationMillis, double greatChance,
                              double jackpotChance, List<String> synergies) {
    }

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
    private final ProgressionScale scale;
    private GameDesignService gameDesignService;
    private final Map<Long, EventRecord> eventsByNode = new ConcurrentHashMap<>();
    private final Map<Long, PassiveResearchRecord> passiveResearch = new ConcurrentHashMap<>();
    private final AtomicLong nextEventId = new AtomicLong(1);
    private BukkitRunnable task;

    public ExplorationService(IdleFarmPlugin plugin, Database database,
                              NodeStore nodeStore, WorkerStore workerStore) {
        this.plugin = plugin;
        this.database = database;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.scale = new ProgressionScale(plugin);
    }

    public void setGameDesignService(GameDesignService gameDesignService) {
        this.gameDesignService = gameDesignService;
    }

    private static final class PassiveResearchRecord {
        volatile long lastAt;
        volatile String day;
        volatile int earnedToday;

        PassiveResearchRecord(long lastAt, String day, int earnedToday) {
            this.lastAt = lastAt;
            this.day = day;
            this.earnedToday = earnedToday;
        }
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
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT node_id, last_research_at, research_day, earned_today "
                             + "FROM idlefarm_node_research");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                passiveResearch.put(rs.getLong("node_id"), new PassiveResearchRecord(
                        rs.getLong("last_research_at"),
                        rs.getString("research_day"),
                        rs.getInt("earned_today")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load passive research: " + e.getMessage());
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
        return scale.bracket(node.getExplorationLevel());
    }

    public long expForNextExplorationLevel(int level) {
        return scale.expForNextLevel(level);
    }

    /** Grants exploration EXP to a node, applying level-ups. Caller persists. */
    public void grantExplorationExp(NodeRecord node, long amount) {
        int cap = scale.levelCap();
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
        if (gameDesignService != null) {
            gameDesignService.onNodeLevel(node);
        }
    }

    /**
     * Grants time-based passive research independently of item throughput.
     * Progress is capped per node/day and downtime is limited by Rested
     * Research, so fast node types and production boosters cannot level faster.
     */
    public long advancePassiveResearch(NodeRecord node, List<WorkerRecord> crew,
                                       long now, boolean bufferFull) {
        PassiveResearchRecord progress = passiveResearch.computeIfAbsent(node.getId(),
                ignored -> new PassiveResearchRecord(node.getLastTickAt(),
                        LocalDate.now().toString(), 0));
        long previous = progress.lastAt > 0 ? progress.lastAt : now;
        long elapsed = Math.max(0, Math.min(now - previous, scale.restedResearchMillis()));
        progress.lastAt = now;

        String today = LocalDate.now().toString();
        if (!today.equals(progress.day)) {
            progress.day = today;
            progress.earnedToday = 0;
        }

        int remaining = Math.max(0, scale.passiveResearchDailyCap() - progress.earnedToday);
        long earned = 0;
        if (remaining > 0 && elapsed > 0 && node.getExplorationLevel() < scale.levelCap()) {
            double rate = scale.passiveResearchPerHour(crew, bufferFull);
            if (bufferFull && gameDesignService != null) {
                double configured = plugin.getConfig().getDouble(
                        "exploration.passive-research.full-buffer-multiplier", 0.25);
                if (configured > 0) {
                    rate *= gameDesignService.fullBufferResearchMultiplier(node) / configured;
                }
            }
            earned = Math.min(remaining, (long) Math.floor(rate * elapsed / 3_600_000.0));
            if (earned > 0) {
                progress.earnedToday += (int) earned;
                grantExplorationExp(node, earned);
            }
        }
        persistPassiveResearch(node.getId(), progress);
        return earned;
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
        long waitingForOwner = eventsByNode.entrySet().stream()
                .filter(entry -> {
                    NodeRecord other = nodeStore.getAll().stream()
                            .filter(candidate -> candidate.getId() == entry.getKey())
                            .findFirst().orElse(null);
                    return other != null && other.getOwnerUuid().equals(node.getOwnerUuid());
                }).count();
        int inboxCap = plugin.getConfig().getInt("exploration.max-waiting-events", 3);
        if (waitingForOwner >= inboxCap) {
            return;
        }
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
        node.setExplorationLevel(Math.max(1, Math.min(scale.levelCap(), level)));
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

        TeamPreview preview = preview(node, teamSize);
        long duration = preview.durationMillis();
        String preparation = gameDesignService == null ? "NONE"
                : gameDesignService.activatePreparation(node);
        if ("SPEED".equals(preparation)) duration = Math.round(duration * 0.85);

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

    public TeamPreview preview(NodeRecord node, int teamSize) {
        EventRecord event = eventsByNode.get(node.getId());
        if (event == null) return new TeamPreview(0, 0, 0, 0, List.of());
        List<WorkerRecord> idle = workerStore.getAssigned(node.getId()).stream()
                .filter(worker -> !WorkerRecord.STATE_EXPLORING.equals(worker.getState())).toList();
        List<WorkerRecord> team = idle.subList(0, Math.min(Math.max(0, teamSize), idle.size()));
        int speedSum = team.stream().mapToInt(worker -> worker.getStats().speed()).sum();
        if (gameDesignService != null) {
            speedSum += team.stream().filter(worker -> "TRAIL_BOOTS".equals(
                    gameDesignService.workerCharm(worker.getWorkerUuid()))).mapToInt(worker -> 20).sum();
            if ("LONG_ROUTES".equals(gameDesignService.seasonModifier())) {
                speedSum = (int) Math.round(speedSum * 1.25);
            }
        }
        long baseMinutes = eventConfig(event.eventType).getLong("duration-minutes", 20);
        long duration = (long) (baseMinutes * 60_000L / (1 + speedSum / 100.0));
        int luck = team.stream().mapToInt(worker -> worker.getStats().luck()).sum();
        double jackpot = plugin.getConfig().getDouble("exploration.grade-odds.jackpot-base", 5)
                + luck * plugin.getConfig().getDouble("exploration.grade-odds.jackpot-per-luck", 0.1);
        double great = plugin.getConfig().getDouble("exploration.grade-odds.great-base", 20)
                + luck * plugin.getConfig().getDouble("exploration.grade-odds.great-per-luck", 0.2);
        if (gameDesignService != null && team.stream().anyMatch(worker -> "LUCKY_TOKEN".equals(
                gameDesignService.workerCharm(worker.getWorkerUuid())))) {
            great += 3.0;
        }
        java.util.Set<String> roles = team.stream().map(this::role).collect(java.util.stream.Collectors.toSet());
        List<String> synergies = new ArrayList<>();
        if (roles.size() >= 3) synergies.add("Specialists +10% TeamScore");
        if (roles.contains("Researcher") && roles.contains("Runner")) synergies.add("Survey Corps +15% Node EXP");
        if (roles.contains("Scout") && roles.contains("Producer")) synergies.add("Treasure Crew +10% loot");
        if (roles.size() >= 4) synergies.add("Full House");
        return new TeamPreview(team.size(), duration, Math.min(100 - jackpot, great),
                Math.min(100, jackpot), List.copyOf(synergies));
    }

    private String role(WorkerRecord worker) {
        var stats = worker.getStats();
        int max = Math.max(Math.max(stats.diligence(), stats.luck()),
                Math.max(stats.stamina(), stats.speed()));
        int min = Math.min(Math.min(stats.diligence(), stats.luck()),
                Math.min(stats.stamina(), stats.speed()));
        if (worker.getTrait() == dev.branzx.idlefarm.worker.Trait.BALANCED && max - min <= 5) return "Leader";
        if (max == stats.diligence()) return "Producer";
        if (max == stats.luck()) return "Scout";
        if (max == stats.stamina()) return "Researcher";
        return "Runner";
    }

    private void complete(NodeRecord node, EventRecord event) {
        List<WorkerRecord> team = teamOf(event);
        String preparation = gameDesignService == null ? "NONE"
                : gameDesignService.finishPreparation(node);
        int luckSum = team.stream().mapToInt(w -> w.getStats().luck()).sum();
        double extraGreat = gameDesignService != null && team.stream().anyMatch(worker ->
                "LUCKY_TOKEN".equals(gameDesignService.workerCharm(worker.getWorkerUuid()))) ? 3.0 : 0;
        event.grade = rollGrade(luckSum, extraGreat);
        java.util.Set<String> roles = team.stream().map(this::role).collect(java.util.stream.Collectors.toSet());
        event.loot = rollLoot(event.eventType, event.grade, team.size(),
                roles.contains("Scout") && roles.contains("Producer"),
                "QUANTITY".equals(preparation));
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
        Map<String, Integer> loot = parseLoot(event.loot);
        remove(event);
        return loot;
    }

    /**
     * Claims a completed event directly to the owner's Warehouse. The event
     * remains claimable when the full bundle does not fit, preventing partial
     * deposits and lost rewards.
     */
    public WarehouseClaimResult claimToWarehouse(NodeRecord node, WarehouseService warehouse) {
        EventRecord event = eventsByNode.get(node.getId());
        if (event == null || !"COMPLETED".equals(event.state)) {
            return new WarehouseClaimResult(false, 0, "No completed expedition to claim.");
        }
        Map<String, Integer> loot = parseLoot(event.loot);
        int total = loot.values().stream().mapToInt(Integer::intValue).sum();
        int free = warehouse.freeSpace(node.getOwnerUuid());
        if (!warehouse.depositAll(node.getOwnerUuid(), loot)) {
            return new WarehouseClaimResult(false, 0,
                    "Warehouse needs " + Math.max(0, total - free) + " more free space.");
        }
        if (gameDesignService != null) {
            for (Map.Entry<String, Integer> entry : loot.entrySet()) {
                gameDesignService.discover(node.getOwnerUuid(), node.getType(),
                        entry.getKey(), entry.getValue());
            }
            gameDesignService.onExplorationClaimed(node, event.grade);
        }
        remove(event);
        return new WarehouseClaimResult(true, total,
                "Expedition loot → Warehouse: " + total + " items!");
    }

    /** Cancels any event on unclaim/convert: workers return, no loot. */
    public void cancel(NodeRecord node) {
        EventRecord event = eventsByNode.get(node.getId());
        if (event == null) {
            return;
        }
        if (gameDesignService != null) {
            gameDesignService.cancelPreparation(node);
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

    private String rollGrade(int luckSum, double extraGreat) {
        double jackpot = plugin.getConfig().getDouble("exploration.grade-odds.jackpot-base", 5)
                + luckSum * plugin.getConfig().getDouble("exploration.grade-odds.jackpot-per-luck", 0.1);
        double great = plugin.getConfig().getDouble("exploration.grade-odds.great-base", 20)
                + luckSum * plugin.getConfig().getDouble("exploration.grade-odds.great-per-luck", 0.2)
                + extraGreat;
        double roll = ThreadLocalRandom.current().nextDouble() * 100;
        if (roll < jackpot) {
            return "JACKPOT";
        }
        if (roll < jackpot + great) {
            return "GREAT";
        }
        return "NORMAL";
    }

    private String rollLoot(String eventType, String grade, int teamSize,
                            boolean treasureCrew, boolean preparedQuantity) {
        ConfigurationSection config = eventConfig(eventType);
        ConfigurationSection table = config.getConfigurationSection("loot");
        int baseCount = config.getInt("loot-count-base", 8);
        double gradeMultiplier = switch (grade) {
            case "JACKPOT" -> plugin.getConfig().getDouble("exploration.grade-multiplier.jackpot", 3.0);
            case "GREAT" -> plugin.getConfig().getDouble("exploration.grade-multiplier.great", 1.8);
            default -> 1.0;
        };
        int count = (int) Math.round(baseCount * gradeMultiplier * (1 + (teamSize - 1) * 0.5)
                * (treasureCrew ? 1.10 : 1.0));
        if (preparedQuantity) count = (int) Math.round(count * 1.15);

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

    private Map<String, Integer> parseLoot(String serialized) {
        Map<String, Integer> loot = new ConcurrentHashMap<>();
        if (serialized == null || serialized.isBlank()) {
            return loot;
        }
        for (String entry : serialized.split(";")) {
            int colon = entry.indexOf(':');
            if (colon > 0) {
                loot.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
            }
        }
        return loot;
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

    private void persistPassiveResearch(long nodeId, PassiveResearchRecord progress) {
        long lastAt = progress.lastAt;
        String day = progress.day;
        int earnedToday = progress.earnedToday;
        database.submitWrite(() -> {
            String sql = database.isSqlite()
                    ? "INSERT INTO idlefarm_node_research "
                    + "(node_id, last_research_at, research_day, earned_today) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(node_id) DO UPDATE SET last_research_at=excluded.last_research_at, "
                    + "research_day=excluded.research_day, earned_today=excluded.earned_today"
                    : "INSERT INTO idlefarm_node_research "
                    + "(node_id, last_research_at, research_day, earned_today) VALUES (?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE last_research_at=VALUES(last_research_at), "
                    + "research_day=VALUES(research_day), earned_today=VALUES(earned_today)";
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(sql)) {
                upsert.setLong(1, nodeId);
                upsert.setLong(2, lastAt);
                upsert.setString(3, day);
                upsert.setInt(4, earnedToday);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist passive research for node "
                        + nodeId + ": " + e.getMessage());
            }
        });
    }
}
