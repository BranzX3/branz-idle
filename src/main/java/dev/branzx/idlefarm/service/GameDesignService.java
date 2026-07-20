package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.worker.WorkerRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persistent implementation of the cross-cutting game-design rules.  The
 * older services remain responsible for their own transactions; this service
 * owns Focus, onboarding, daily/weekly progression, Chronicle state,
 * discoveries, rare caps, node builds, projects, seasons and telemetry.
 *
 * State is deliberately stored as scoped key/value rows.  New seasonal goals
 * can be added without destructive schema changes and every mutation still has
 * a stable primary key, making rewards idempotent.
 */
public final class GameDesignService {

    public record Result(boolean success, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    public record Commission(String id, String description, int current, int target,
                             String reward, boolean claimed) {
    }

    public record Achievement(String id, String name, String description, int points,
                              boolean completed, boolean claimed) {
    }

    public record Project(String id, String name, String material, int current, int target,
                          boolean completed) {
    }

    private record AchievementDefinition(String id, String name, String description, int points) {
    }

    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Bangkok");
    private static final long FOCUS_COOLDOWN_MS = 24L * 60 * 60 * 1000;
    private static final UUID SERVER_SCOPE = new UUID(0, 0);
    private static final Set<String> RARE_DAILY =
            Set.of("DIAMOND", "EMERALD", "NAUTILUS_SHELL", "GHAST_TEAR");
    private static final Set<String> RARE_WEEKLY =
            Set.of("ANCIENT_DEBRIS", "NETHERITE_SCRAP", "WITHER_SKELETON_SKULL");

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final NodeStore nodeStore;
    private final PlayerDataStore dataStore;
    private final AuditService audit;
    private final GameStateStore stateStore;
    private final ProgressionRewards rewards;
    private final Map<String, Integer> capCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> discoveries = new ConcurrentHashMap<>();
    private final Map<String, AchievementDefinition> achievementDefinitions = new LinkedHashMap<>();
    private WarehouseService warehouse;
    private ExplorationService exploration;

    public GameDesignService(IdleFarmPlugin plugin, Database database, NodeStore nodeStore,
                             PlayerDataStore dataStore, AuditService audit) {
        this.plugin = plugin;
        this.database = database;
        this.nodeStore = nodeStore;
        this.dataStore = dataStore;
        this.audit = audit;
        this.stateStore = new GameStateStore(plugin, database);
        this.rewards = ProgressionRewards.from(plugin);
    }

    public void setRuntimeServices(WarehouseService warehouse, ExplorationService exploration) {
        this.warehouse = warehouse;
        this.exploration = exploration;
    }

    public ProgressionRewards progressionRewards() {
        return rewards;
    }

    public void loadAllSync() {
        loadAchievementDefinitions();
        try {
            stateStore.loadAllSync();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load scoped game state: " + e.getMessage());
        }
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, node_type, material, lifetime_count FROM idlefarm_discoveries");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    discoveries.put(discoveryKey(UUID.fromString(rs.getString("owner_uuid")),
                                    rs.getString("node_type"), rs.getString("material")),
                            rs.getLong("lifetime_count"));
                }
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT owner_uuid, material, period_key, amount FROM idlefarm_resource_caps");
                 ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    capCounts.put(capKey(UUID.fromString(rs.getString("owner_uuid")),
                            rs.getString("material"), rs.getString("period_key")), rs.getInt("amount"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load game-design state: " + e.getMessage());
        }
    }

    private void loadAchievementDefinitions() {
        File file = new File(plugin.getDataFolder(), "achievements.yml");
        if (!file.exists()) {
            plugin.saveResource("achievements.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            if (section == null) continue;
            achievementDefinitions.put(id, new AchievementDefinition(id,
                    section.getString("name", pretty(id)),
                    section.getString("description", ""),
                    Math.max(0, section.getInt("chronicle-points", 1))));
        }
    }

    // ---- Focus and onboarding -------------------------------------------------

    public Long focusedNode(UUID owner) {
        String value = get(owner, "ACCOUNT", "-", "focused_node");
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean isFocused(NodeRecord node) {
        Long focused = focusedNode(node.getOwnerUuid());
        return focused != null && focused == node.getId();
    }

    public Result setFocus(UUID owner, NodeRecord node, boolean freeOverride) {
        if (node == null || !node.getOwnerUuid().equals(owner) || !node.getType().isProduction()) {
            return Result.fail("Choose one of your Production Nodes.");
        }
        long changedAt = getLong(owner, "ACCOUNT", "-", "focus_changed_at", 0);
        long remaining = FOCUS_COOLDOWN_MS - (System.currentTimeMillis() - changedAt);
        if (!freeOverride && changedAt > 0 && remaining > 0) {
            return Result.fail("Focus can change again in " + formatDuration(remaining) + ".");
        }
        put(owner, "ACCOUNT", "-", "focused_node", String.valueOf(node.getId()));
        put(owner, "ACCOUNT", "-", "focus_changed_at", String.valueOf(System.currentTimeMillis()));
        audit.log(owner, "FOCUS_CHANGE", "{\"node\":" + node.getId() + "}");
        telemetry(owner, "FOCUS_CHANGE", "{\"node\":" + node.getId() + "}");
        return Result.ok("Focused Node set to " + node.getType() + " Lv." + node.getExplorationLevel() + ".");
    }

    public boolean firstResidentialIsFree(UUID owner) {
        return nodeStore.getByOwner(owner).stream().noneMatch(n -> n.getType() == NodeType.RESIDENTIAL);
    }

    public void onLogin(UUID owner) {
        String previous = get(owner, "ACCOUNT", "-", "last_login_day");
        LocalDate today = LocalDate.now(GAME_ZONE);
        if (previous != null) {
            try {
                long missed = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(previous), today) - 1;
                if (missed > 0) {
                    int simplified = (int) Math.min(3, missed);
                    put(owner, "ACCOUNT", "-", "catchup_commissions", String.valueOf(simplified));
                }
            } catch (Exception ignored) {
                // A malformed legacy date should never block login.
            }
        }
        put(owner, "ACCOUNT", "-", "last_login_day", today.toString());
    }

    public boolean firstProductionIsFree(UUID owner) {
        return nodeStore.getByOwner(owner).stream().noneMatch(n -> n.getType().isProduction());
    }

    /** Called after an authoritative claim. Returns true for the first production node. */
    public boolean onNodeClaimed(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        if (node.getType() == NodeType.RESIDENTIAL) {
            completeAchievement(owner, "place_to_begin");
            telemetry(owner, "FIRST_RESIDENTIAL", "{\"node\":" + node.getId() + "}");
            return false;
        }
        boolean first = !"1".equals(get(owner, "ACCOUNT", "-", "starter_production"));
        increment(owner, "ACCOUNT", "-", "production_types_" + node.getType().name(), 1);
        if (first) {
            put(owner, "ACCOUNT", "-", "starter_production", "1");
            setFocus(owner, node, true);
            grantNodeExp(node, rewards.starterNodeExp());
            if (exploration != null) {
                exploration.adminSpawn(node, "first_survey");
            }
            completeAchievement(owner, "first_camp");
            telemetry(owner, "FIRST_PRODUCTION", "{\"node\":" + node.getId() + "}");
        }
        return first;
    }

    public void markStarterWorker(UUID owner, UUID worker) {
        put(owner, "ACCOUNT", "-", "starter_worker", worker.toString());
    }

    public boolean isStarterWorker(UUID worker) {
        if (worker == null) return false;
        String id = worker.toString();
        return stateStore.containsValue("ACCOUNT", "starter_worker", id);
    }

    public boolean toggleWorkerLock(UUID owner, UUID worker) {
        String current = get(owner, "WORKER", worker.toString(), "locked");
        boolean next = !"1".equals(current);
        put(owner, "WORKER", worker.toString(), "locked", next ? "1" : "0");
        return next;
    }

    public boolean isWorkerLocked(UUID worker) {
        if (worker == null) return false;
        String id = worker.toString();
        return "1".equals(stateStore.findByScopeId("WORKER", id, "locked"));
    }

    public String workerCharm(UUID owner, UUID worker) {
        return valueOr(get(owner, "WORKER", worker.toString(), "charm"), "NONE");
    }

    public String workerCharm(UUID worker) {
        if (worker == null) return "NONE";
        String id = worker.toString();
        return valueOr(stateStore.findByScopeId("WORKER", id, "charm"), "NONE");
    }

    public Result equipCharm(UUID owner, WorkerRecord worker, String charm) {
        if (worker == null) return Result.fail("Worker not found.");
        boolean unlocked = projects(owner).stream().anyMatch(Project::completed);
        if (worker.getAssignedNodeId() != null) {
            unlocked |= nodeStore.getByOwner(owner).stream()
                    .anyMatch(node -> node.getId() == worker.getAssignedNodeId()
                            && node.getExplorationLevel() >= 50);
        }
        if (!unlocked) return Result.fail("Charm slot unlocks at Node Lv.50 or from a completed Project.");
        String normalized = charm.toUpperCase(Locale.ROOT);
        if (!Set.of("NONE", "SURVEY_COMPASS", "HEAVY_GLOVES", "LUCKY_TOKEN", "TRAIL_BOOTS")
                .contains(normalized)) {
            return Result.fail("Unknown Charm.");
        }
        put(owner, "WORKER", worker.getWorkerUuid().toString(), "charm", normalized);
        return Result.ok("Charm equipped: " + pretty(normalized) + ".");
    }

    public long trainingNotes(UUID owner) {
        return getLong(owner, "ACCOUNT", "-", "training_notes", 0);
    }

    public void addTrainingNotes(UUID owner, long amount) {
        if (amount > 0) increment(owner, "ACCOUNT", "-", "training_notes", amount);
    }

    public long takeTrainingNotes(UUID owner, long requested) {
        long available = trainingNotes(owner);
        long taken = Math.min(available, Math.max(0, requested));
        if (taken > 0) put(owner, "ACCOUNT", "-", "training_notes", String.valueOf(available - taken));
        return taken;
    }

    public void onWorkerAssigned(NodeRecord node) {
        completeAchievement(node.getOwnerUuid(), "first_shift");
        advanceCommission(node, "crew", 1);
        telemetry(node.getOwnerUuid(), "WORKER_ASSIGNED", "{\"node\":" + node.getId() + "}");
    }

    // ---- Reward cadence and commissions --------------------------------------

    public void onBufferCollected(NodeRecord node, int amount) {
        if (amount <= 0) return;
        UUID owner = node.getOwnerUuid();
        String day = dayKey();
        if (isFocused(node) && claimOnce(owner, "DAILY", day, "first_collection")) {
            grantNodeExp(node, rewards.firstCollectionExp());
        }
        increment(owner, "NODE", String.valueOf(node.getId()), "collected_total", amount);
        advanceCommission(node, "collect", amount);
        completeAchievement(owner, "supplies_arrive");
        telemetry(owner, "BUFFER_COLLECTED",
                "{\"node\":" + node.getId() + ",\"amount\":" + amount + "}");
    }

    public void onItemsProduced(NodeRecord node, Map<String, Integer> produced) {
        int total = produced.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return;
        UUID owner = node.getOwnerUuid();
        increment(owner, "NODE", String.valueOf(node.getId()), "produced_total", total);
        advanceCommission(node, "produce", total);
        for (Map.Entry<String, Integer> entry : produced.entrySet()) {
            discover(owner, node.getType(), entry.getKey(), entry.getValue());
        }
        telemetry(owner, "ITEM_PRODUCED",
                "{\"node\":" + node.getId() + ",\"amount\":" + total + "}");
    }

    public void onExplorationClaimed(NodeRecord node, String grade) {
        UUID owner = node.getOwnerUuid();
        String day = dayKey();
        long exp;
        if (isFocused(node) && claimOnce(owner, "DAILY", day, "first_expedition")) {
            exp = rewards.firstExpeditionExp();
        } else {
            int extra = getInt(owner, "DAILY", day, "extra_expeditions", 0);
            exp = extra < rewards.extraExpeditionsPerDay()
                    ? rewards.extraExpeditionExp() : 0;
            if (extra < rewards.extraExpeditionsPerDay()) {
                put(owner, "DAILY", day, "extra_expeditions", String.valueOf(extra + 1));
            }
        }
        grantNodeExp(node, Math.round(exp * eventExpMultiplier(node)));
        increment(owner, "NODE", String.valueOf(node.getId()), "events_total", 1);
        advanceCommission(node, "expedition", 1);
        completeAchievement(owner, "beyond_fence");
        if ("GREAT".equals(grade) || "JACKPOT".equals(grade)) {
            put(owner, "NODE", String.valueOf(node.getId()), "great_event", "1");
        }
        telemetry(owner, "EXPLORATION_COMPLETED",
                "{\"node\":" + node.getId() + ",\"grade\":\"" + safe(grade) + "\"}");
    }

    public List<Commission> commissions(UUID owner) {
        NodeRecord focus = focusedRecord(owner);
        if (focus == null) return List.of();
        String day = dayKey();
        ensureDailyCommissions(owner, focus, day);
        List<Commission> result = new ArrayList<>(List.of(
                commission(owner, day, "focus", "Advance your Focused Node", 1,
                        rewards.focusCommissionExp() + " Node EXP"),
                commission(owner, day, "behavior", "Complete a worker or expedition action", 1,
                        rewards.behaviorCommissionCoins() + " Coins"),
                commission(owner, day, "supply", "Deliver 64 mixed unlocked resources", 64,
                        rewards.supplyCommissionChroniclePoints() + " Chronicle Points")));
        int catchup = getInt(owner, "ACCOUNT", "-", "catchup_commissions", 0);
        if (catchup > 0) {
            result.add(new Commission("catchup", "Returning-player simplified commission",
                    1, 1, rewards.catchupCommissionExp() + " Node EXP (catch-up)",
                    "1".equals(get(owner, "DAILY", day, "commission_catchup_claimed"))));
        }
        return List.copyOf(result);
    }

    public Result claimCommission(UUID owner, String slot) {
        NodeRecord focus = focusedRecord(owner);
        if (focus == null) return Result.fail("Select a Focused Node first.");
        String day = dayKey();
        ensureDailyCommissions(owner, focus, day);
        Commission commission = commissions(owner).stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(slot)).findFirst().orElse(null);
        if (commission == null) return Result.fail("Unknown commission slot.");
        if (commission.claimed()) return Result.fail("That commission was already claimed.");
        if (commission.current() < commission.target()) return Result.fail("Commission is not complete yet.");
        switch (slot.toLowerCase(Locale.ROOT)) {
            case "focus" -> grantNodeExp(focus, rewards.focusCommissionExp());
            case "behavior" -> addCoins(owner, rewards.behaviorCommissionCoins());
            case "supply" -> {
                if (consumeMixedWarehouse(owner, 64) < 64) {
                    return Result.fail("Warehouse no longer has the required 64 resources.");
                }
                increment(owner, "ACCOUNT", "-", "chronicle_points",
                        rewards.supplyCommissionChroniclePoints());
            }
            case "catchup" -> {
                int available = getInt(owner, "ACCOUNT", "-", "catchup_commissions", 0);
                if (available <= 0) return Result.fail("No catch-up commission is available.");
                grantNodeExp(focus, rewards.catchupCommissionExp());
                put(owner, "ACCOUNT", "-", "catchup_commissions", String.valueOf(available - 1));
            }
            default -> { return Result.fail("Unknown commission slot."); }
        }
        put(owner, "DAILY", day, "commission_" + slot + "_claimed", "1");
        audit.log(owner, "COMMISSION_CLAIM", "{\"slot\":\"" + safe(slot) + "\",\"day\":\"" + day + "\"}");
        telemetry(owner, "COMMISSION_CLAIM", "{\"slot\":\"" + safe(slot) + "\"}");
        return Result.ok("Commission reward claimed.");
    }

    public Result claimWeeklyChapter(UUID owner) {
        NodeRecord focus = focusedRecord(owner);
        if (focus == null) return Result.fail("Select a Focused Node first.");
        String week = weekKey();
        int progress = getInt(owner, "WEEKLY", week, "chapter_progress", 0);
        if (progress < 5) return Result.fail("Weekly Chapter needs 5 daily actions (" + progress + "/5).");
        if (!claimOnce(owner, "WEEKLY", week, "chapter_claimed")) {
            return Result.fail("Weekly Chapter already claimed.");
        }
        grantNodeExp(focus, rewards.weeklyChapterExp());
        addCoins(owner, rewards.weeklyChapterCoins());
        audit.log(owner, "CHAPTER_CLAIM", "{\"week\":\"" + week + "\"}");
        return Result.ok("Weekly Node Chapter claimed: +" + rewards.weeklyChapterExp()
                + " Node EXP and +" + rewards.weeklyChapterCoins() + " Coins.");
    }

    private void advanceCommission(NodeRecord node, String action, int amount) {
        if (!isFocused(node)) return;
        UUID owner = node.getOwnerUuid();
        String day = dayKey();
        ensureDailyCommissions(owner, node, day);
        if ("expedition".equals(action) || "crew".equals(action)) {
            put(owner, "DAILY", day, "commission_behavior_progress", "1");
        }
        put(owner, "DAILY", day, "commission_focus_progress", "1");
        // A Weekly Chapter asks for activity across five distinct days, not
        // five rapid actions in one session.
        if (claimOnce(owner, "DAILY", day, "weekly_chapter_action")) {
            int weekly = getInt(owner, "WEEKLY", weekKey(), "chapter_progress", 0);
            put(owner, "WEEKLY", weekKey(), "chapter_progress",
                    String.valueOf(Math.min(5, weekly + 1)));
        }
    }

    private void ensureDailyCommissions(UUID owner, NodeRecord focus, String day) {
        if (get(owner, "DAILY", day, "commission_seed") != null) return;
        put(owner, "DAILY", day, "commission_seed",
                focus.getType().name() + ":" + Math.max(1, focus.getExplorationLevel() / 10 + 1));
        put(owner, "DAILY", day, "commission_focus_progress", "0");
        put(owner, "DAILY", day, "commission_behavior_progress", "0");
        put(owner, "DAILY", day, "commission_supply_progress", "0");
    }

    private Commission commission(UUID owner, String day, String slot, String description,
                                  int defaultTarget, String reward) {
        int target = "supply".equals(slot) ? 64 : defaultTarget;
        int progress = "supply".equals(slot) && warehouse != null
                ? Math.min(target, warehouse.getContents(owner).entrySet().stream()
                        .filter(entry -> isCommissionSupply(entry.getKey()))
                        .mapToInt(Map.Entry::getValue).sum())
                : getInt(owner, "DAILY", day, "commission_" + slot + "_progress", 0);
        boolean claimed = "1".equals(get(owner, "DAILY", day, "commission_" + slot + "_claimed"));
        return new Commission(slot, description, progress, target, reward, claimed);
    }

    private int consumeMixedWarehouse(UUID owner, int requested) {
        if (warehouse == null) return 0;
        Map<String, Integer> contents = warehouse.getContents(owner);
        int available = contents.entrySet().stream().filter(entry -> isCommissionSupply(entry.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        if (available < requested) return 0;
        int remaining = requested;
        int removed = 0;
        for (Map.Entry<String, Integer> entry : List.copyOf(contents.entrySet())) {
            if (remaining <= 0) break;
            if (!isCommissionSupply(entry.getKey())) continue;
            int amount = warehouse.withdraw(owner, entry.getKey(), Math.min(remaining, entry.getValue()));
            remaining -= amount;
            removed += amount;
        }
        return removed;
    }

    private boolean isCommissionSupply(String material) {
        String id = material.toUpperCase(Locale.ROOT);
        return !RARE_DAILY.contains(id) && !RARE_WEEKLY.contains(id)
                && org.bukkit.Material.matchMaterial(id) != null;
    }

    // ---- Discoveries and rare-resource caps ----------------------------------

    public boolean allowResource(UUID owner, String material, int nodeLevel) {
        String normalized = material.toUpperCase(Locale.ROOT);
        int cap;
        String period;
        if (RARE_DAILY.contains(normalized)) {
            period = dayKey();
            cap = switch (normalized) {
                case "DIAMOND" -> nodeLevel >= 80 ? 16 : 8;
                case "EMERALD" -> 12;
                default -> 4;
            };
        } else if (RARE_WEEKLY.contains(normalized)) {
            period = weekKey();
            cap = ("ANCIENT_DEBRIS".equals(normalized) || "NETHERITE_SCRAP".equals(normalized)) ? 2 : 1;
        } else {
            return true;
        }
        String capMaterial = ("ANCIENT_DEBRIS".equals(normalized) || "NETHERITE_SCRAP".equals(normalized))
                ? "ANCIENT_MATERIAL" : normalized;
        String key = capKey(owner, capMaterial, period);
        int used = capCounts.getOrDefault(key, 0);
        if (used >= cap) {
            telemetry(owner, "RARE_CAP_HIT", "{\"material\":\"" + normalized + "\"}");
            return false;
        }
        capCounts.put(key, used + 1);
        persistCap(owner, capMaterial, period, used + 1);
        return true;
    }

    public Map<String, Long> discoveries(UUID owner, NodeType type) {
        Map<String, Long> result = new LinkedHashMap<>();
        String prefix = owner + "|" + type.name() + "|";
        discoveries.forEach((key, value) -> {
            if (key.startsWith(prefix)) result.put(key.substring(prefix.length()), value);
        });
        return result;
    }

    public void discover(UUID owner, NodeType type, String material, int amount) {
        if (amount <= 0) return;
        String normalized = material.toUpperCase(Locale.ROOT);
        String key = discoveryKey(owner, type.name(), normalized);
        boolean fresh = !discoveries.containsKey(key);
        long count = discoveries.merge(key, (long) amount, Long::sum);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                int updated;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE idlefarm_discoveries SET lifetime_count = ? "
                                + "WHERE owner_uuid = ? AND node_type = ? AND material = ?")) {
                    update.setLong(1, count);
                    update.setString(2, owner.toString());
                    update.setString(3, type.name());
                    update.setString(4, normalized);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO idlefarm_discoveries "
                                    + "(owner_uuid, node_type, material, lifetime_count) VALUES (?, ?, ?, ?)")) {
                        insert.setString(1, owner.toString());
                        insert.setString(2, type.name());
                        insert.setString(3, normalized);
                        insert.setLong(4, count);
                        insert.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist discovery: " + e.getMessage());
            }
        });
        if (fresh) {
            increment(owner, "ACCOUNT", "-", "unique_discoveries", 1);
            telemetry(owner, "RESOURCE_DISCOVERED",
                    "{\"type\":\"" + type + "\",\"material\":\"" + normalized + "\"}");
        }
    }

    // ---- Chronicle -----------------------------------------------------------

    public List<Achievement> achievements(UUID owner) {
        return achievementDefinitions.values().stream()
                .map(definition -> achievement(owner, definition.id(), definition.name(),
                        definition.description(), definition.points()))
                .toList();
    }

    public int chroniclePoints(UUID owner) {
        return getInt(owner, "ACCOUNT", "-", "chronicle_points", 0);
    }

    public int seasonalChroniclePoints(UUID owner) {
        return getInt(owner, "SEASON", seasonId(), "chronicle_points", 0);
    }

    public Result claimAchievement(UUID owner, String id) {
        Achievement achievement = achievements(owner).stream()
                .filter(a -> a.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (achievement == null) return Result.fail("Unknown Chronicle achievement.");
        if (!achievement.completed()) return Result.fail("Achievement is not complete.");
        if (achievement.claimed()) return Result.fail("Achievement reward already claimed.");
        put(owner, "ACHIEVEMENT", id, "claimed", "1");
        increment(owner, "ACCOUNT", "-", "chronicle_points", achievement.points());
        if ("supplies_arrive".equals(id)) addCoins(owner, 250);
        audit.log(owner, "ACHIEVEMENT_CLAIM", "{\"id\":\"" + safe(id) + "\"}");
        return Result.ok(achievement.name() + " claimed: +" + achievement.points() + " Chronicle Points.");
    }

    public void onNodeLevel(NodeRecord node) {
        if (node.getExplorationLevel() >= 100) {
            put(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "frontier_eligible", "1");
            completeAchievement(node.getOwnerUuid(), "node_master_100");
            telemetry(node.getOwnerUuid(), "NODE_LEVEL_100", "{\"node\":" + node.getId() + "}");
        }
    }

    private Achievement achievement(UUID owner, String id, String name, String description, int points) {
        boolean completed = "1".equals(get(owner, "ACHIEVEMENT", id, "completed"));
        boolean claimed = "1".equals(get(owner, "ACHIEVEMENT", id, "claimed"));
        return new Achievement(id, name, description, points, completed, claimed);
    }

    private void completeAchievement(UUID owner, String id) {
        if ("1".equals(get(owner, "ACHIEVEMENT", id, "completed"))) return;
        put(owner, "ACHIEVEMENT", id, "completed", "1");
        telemetry(owner, "ACHIEVEMENT_COMPLETED", "{\"id\":\"" + safe(id) + "\"}");
    }

    // ---- Node specialization and type perks ---------------------------------

    public String specialization(NodeRecord node) {
        return valueOr(get(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "specialization"),
                "UNSELECTED");
    }

    public String refinement(NodeRecord node) {
        return valueOr(get(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "refinement"),
                "UNSELECTED");
    }

    public String mastery(NodeRecord node) {
        return valueOr(get(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "mastery"),
                "UNSELECTED");
    }

    public Result selectBuild(UUID owner, NodeRecord node, String tier, String choice) {
        if (node == null || !node.getOwnerUuid().equals(owner)) return Result.fail("You do not own that node.");
        String normalizedTier = tier.toLowerCase(Locale.ROOT);
        String normalized = choice.toUpperCase(Locale.ROOT);
        int required = switch (normalizedTier) {
            case "specialization" -> 25;
            case "refinement" -> 50;
            case "mastery" -> 75;
            default -> -1;
        };
        if (required < 0) return Result.fail("Use specialization, refinement, or mastery.");
        if (node.getExplorationLevel() < required) return Result.fail("Unlocks at Node Lv." + required + ".");
        if (!validBuildChoice(normalizedTier, normalized, specialization(node))) {
            return Result.fail("That choice is not valid for the current branch.");
        }
        String old = get(owner, "NODE", String.valueOf(node.getId()), normalizedTier);
        if (old != null && !old.equals(normalized)) {
            long cooldown = getLong(owner, "NODE", String.valueOf(node.getId()), "respec_cooldown", 0);
            if (cooldown > System.currentTimeMillis()) {
                return Result.fail("Respec available in " + formatDuration(cooldown - System.currentTimeMillis()) + ".");
            }
            boolean free = !"1".equals(get(owner, "NODE", String.valueOf(node.getId()), "free_respec_used"));
            double cost = free ? 0 : 500 + node.getExplorationLevel() * 25;
            PlayerData data = dataStore.getOnline(owner);
            if (cost > 0 && (data == null || data.getBalance() < cost)) {
                return Result.fail("Respec costs " + (long) cost + " Coins.");
            }
            if (cost > 0) data.addBalance(-cost);
            put(owner, "NODE", String.valueOf(node.getId()), "free_respec_used", "1");
            put(owner, "NODE", String.valueOf(node.getId()), "respec_cooldown",
                    String.valueOf(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));
        }
        put(owner, "NODE", String.valueOf(node.getId()), normalizedTier, normalized);
        audit.log(owner, "NODE_BUILD", "{\"node\":" + node.getId() + ",\"tier\":\""
                + normalizedTier + "\",\"choice\":\"" + normalized + "\"}");
        return Result.ok("Node " + normalizedTier + " set to " + pretty(normalized) + ".");
    }

    public Result selectTypePerk(UUID owner, NodeRecord node, int level, String choice) {
        if (node == null || !node.getOwnerUuid().equals(owner)) return Result.fail("You do not own that node.");
        if (!(level == 15 || level == 35 || level == 60 || level == 85) || node.getExplorationLevel() < level) {
            return Result.fail("That type-perk milestone is not unlocked.");
        }
        List<String> choices = typePerkChoices(node.getType(), level);
        String normalized = choice.toUpperCase(Locale.ROOT);
        if (choices.stream().noneMatch(normalized::equals)) {
            return Result.fail("Choose one of: " + String.join(", ", choices));
        }
        put(owner, "NODE", String.valueOf(node.getId()), "type_perk_" + level, normalized);
        audit.log(owner, "TYPE_PERK", "{\"node\":" + node.getId() + ",\"level\":" + level
                + ",\"choice\":\"" + normalized + "\"}");
        return Result.ok("Type Perk selected: " + pretty(normalized) + ".");
    }

    public List<String> typePerkChoices(NodeType type, int level) {
        List<String> choices = switch (type) {
            case MINING -> switch (level) {
                case 15 -> List.of("STONE_MASON", "ORE_SENSE", "REINFORCED_SHAFT");
                case 35 -> List.of("SEISMIC_MAP", "RICH_VEIN", "CRYSTAL_ECHO");
                case 60 -> List.of("METAL_STRATUM", "REDSTONE_STRATUM", "GEM_STRATUM");
                case 85 -> List.of("DEEP_CORE", "EXCAVATION_CREW", "GEOLOGICAL_ARCHIVE");
                default -> List.of();
            };
            case FARMING -> switch (level) {
                case 15 -> List.of("CROP_ROTATION", "SEED_KEEPER", "IRRIGATION_CHANNELS");
                case 35 -> List.of("GREENHOUSE", "MARKET_GARDEN", "POLLINATOR_ROUTE");
                case 60 -> List.of("STAPLE_HARVEST", "EXOTIC_GARDEN", "ALCHEMISTS_PLOT");
                case 85 -> List.of("PERENNIAL_FIELD", "HARVEST_FESTIVAL", "LIVING_SOIL");
                default -> List.of();
            };
            case WOODCUTTING -> switch (level) {
                case 15 -> List.of("SUSTAINABLE_GROVE", "LUMBER_STACKS", "SAPLING_KEEPER");
                case 35 -> List.of("TRAILBLAZER", "CARPENTERS_MEASURE", "FOREST_MEMORY");
                case 60 -> List.of("TEMPERATE_GROVE", "WILD_GROVE", "OTHERWORLD_GROVE");
                case 85 -> List.of("ELDER_GROVE", "MASTER_CARPENTER", "HEARTWOOD");
                default -> List.of();
            };
            case LIVESTOCK -> switch (level) {
                case 15 -> List.of("BALANCED_HERD", "FEED_RESERVE", "RANCH_HAND");
                case 35 -> List.of("BREEDING_RECORDS", "FISHERY_ROUTE", "SHEPHERDS_CALL");
                case 60 -> List.of("RANCH_TABLE", "WEAVERS_PASTURE", "RIVER_KEEPER");
                case 85 -> List.of("LEGENDARY_HERD", "SANCTUARY", "RANCH_LEGACY");
                default -> List.of();
            };
            case HUNTER -> switch (level) {
                case 15 -> List.of("BOUNTY_BOARD", "SCAVENGER", "NIGHT_WATCH");
                case 35 -> List.of("TRAIL_MARKS", "TROPHY_SENSE", "PREPARED_AMBUSH");
                case 60 -> List.of("GRAVE_WARDEN", "NEST_BREAKER", "RIFT_STALKER");
                case 85 -> List.of("APEX_CONTRACT", "CLEAN_HUNT", "DREAD_MARK");
                default -> List.of();
            };
            default -> List.of();
        };
        return choices.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
    }

    public String typePerk(NodeRecord node, int level) {
        return valueOr(get(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()),
                "type_perk_" + level), "UNSELECTED");
    }

    public double productionMultiplier(NodeRecord node) {
        double result = 1.0;
        if ("INDUSTRY".equals(specialization(node))) result *= 1.15;
        if ("MASS_PRODUCTION".equals(refinement(node))) result *= 1.10;
        return result;
    }

    public double bufferMultiplier(NodeRecord node) {
        double result = 1.0;
        if ("LOGISTICS".equals(specialization(node))) result += 0.50;
        if ("DEEP_STORAGE".equals(refinement(node))) result += 0.50;
        if (Set.of("REINFORCED_SHAFT", "IRRIGATION_CHANNELS", "FEED_RESERVE")
                .contains(typePerk(node, 15))) result += 0.25;
        if ("LUMBER_STACKS".equals(typePerk(node, 15))) result += 0.35;
        return result;
    }

    public double fullBufferResearchMultiplier(NodeRecord node) {
        if ("QUARTERMASTER".equals(mastery(node)) || "GREENHOUSE".equals(typePerk(node, 35))) {
            return 0.50;
        }
        if ("REINFORCED_SHAFT".equals(typePerk(node, 15))) return 0.30;
        return 0.25;
    }

    public double eventExpMultiplier(NodeRecord node) {
        double result = "DEEP_SURVEY".equals(refinement(node)) ? 1.15 : 1.0;
        if ("DREAD_MARK".equals(typePerk(node, 85))) result *= 1.10;
        if ("RESEARCH_WEEK".equals(seasonModifier())) result *= 1.15;
        return result;
    }

    public String seasonModifier() {
        return switch ((seasonWeek() - 1) % 5) {
            case 0 -> "LONG_ROUTES";
            case 1 -> "UNSTABLE_VEINS";
            case 2 -> "RESEARCH_WEEK";
            case 3 -> "MIXED_CREWS";
            default -> "SUPPLY_SHORTAGE";
        };
    }

    /** Direction perks alter relative weight by the locked maximum of 20%. */
    public double resourceWeightMultiplier(NodeRecord node, String material) {
        String perk = typePerk(node, 60);
        String id = material.toUpperCase(Locale.ROOT);
        boolean favored = switch (perk) {
            case "METAL_STRATUM" -> contains(id, "COPPER", "IRON", "GOLD");
            case "REDSTONE_STRATUM" -> contains(id, "REDSTONE", "LAPIS", "QUARTZ", "AMETHYST");
            case "GEM_STRATUM" -> contains(id, "DIAMOND", "EMERALD");
            case "STAPLE_HARVEST" -> contains(id, "WHEAT", "CARROT", "POTATO", "BEETROOT", "PUMPKIN", "MELON");
            case "EXOTIC_GARDEN" -> contains(id, "COCOA", "BERR", "MUSHROOM", "BAMBOO", "CHORUS");
            case "ALCHEMISTS_PLOT" -> contains(id, "NETHER_WART", "HONEY", "GLOW_BERRIES");
            case "TEMPERATE_GROVE" -> contains(id, "OAK", "BIRCH", "SPRUCE");
            case "WILD_GROVE" -> contains(id, "JUNGLE", "ACACIA", "MANGROVE", "CHERRY", "BAMBOO");
            case "OTHERWORLD_GROVE" -> contains(id, "CRIMSON", "WARPED");
            case "RANCH_TABLE" -> contains(id, "BEEF", "PORK", "CHICKEN", "EGG", "MILK");
            case "WEAVERS_PASTURE" -> contains(id, "LEATHER", "WOOL", "FEATHER", "RABBIT_HIDE");
            case "RIVER_KEEPER" -> contains(id, "COD", "SALMON", "FISH", "INK", "NAUTILUS");
            case "GRAVE_WARDEN" -> contains(id, "ROTTEN", "BONE", "PHANTOM");
            case "NEST_BREAKER" -> contains(id, "STRING", "SPIDER", "SLIME", "MAGMA");
            case "RIFT_STALKER" -> contains(id, "ENDER", "BLAZE", "GHAST", "PRISMARINE");
            default -> false;
        };
        return favored ? 1.20 : 1.0;
    }

    // ---- Projects ------------------------------------------------------------

    public List<Project> projects(UUID owner) {
        return List.of(
                project(owner, "storehouse", "Expanded Storehouse", "OAK_LOG", 512),
                project(owner, "expedition_dock", "Expedition Dock", "WOOL", 384),
                project(owner, "chronicle_hall", "Chronicle Hall", "STONE", 1_024));
    }

    public Result contributeProject(UUID owner, String id, int requested) {
        if (warehouse == null) return Result.fail("Warehouse service is not ready.");
        Project project = projects(owner).stream().filter(p -> p.id().equalsIgnoreCase(id))
                .findFirst().orElse(null);
        if (project == null) return Result.fail("Unknown project.");
        if (project.completed()) return Result.fail("Project is already complete.");
        int remaining = project.target() - project.current();
        int removed = warehouse.withdraw(owner, project.material(), Math.min(Math.max(1, requested), remaining));
        if (removed <= 0) return Result.fail("No " + pretty(project.material()) + " in Warehouse.");
        int next = project.current() + removed;
        put(owner, "PROJECT", project.id(), "progress", String.valueOf(next));
        if (next >= project.target()) {
            put(owner, "PROJECT", project.id(), "completed", "1");
            increment(owner, "ACCOUNT", "-", "chronicle_points", 5);
        }
        audit.log(owner, "PROJECT_CONTRIBUTE", "{\"id\":\"" + project.id() + "\",\"amount\":" + removed + "}");
        telemetry(owner, "PROJECT_CONTRIBUTED", "{\"id\":\"" + project.id() + "\",\"amount\":" + removed + "}");
        return Result.ok("Contributed " + removed + " " + pretty(project.material()) + " ("
                + next + "/" + project.target() + ").");
    }

    public Project serverProject() {
        int target = plugin.getConfig().getInt("projects.server.target", 100_000);
        String material = plugin.getConfig().getString("projects.server.material", "COBBLESTONE");
        return project(SERVER_SCOPE, "season_" + seasonId(), "Season Community Monument",
                material, target);
    }

    public Result contributeServerProject(UUID owner, int requested) {
        if (warehouse == null) return Result.fail("Warehouse service is not ready.");
        Project project = serverProject();
        String day = dayKey();
        int dailyCap = plugin.getConfig().getInt("projects.server.daily-cap", 1_024);
        int used = getInt(owner, "DAILY", day, "server_project_contribution", 0);
        int allowed = Math.min(Math.max(0, requested), Math.max(0, dailyCap - used));
        if (allowed <= 0) return Result.fail("Daily Server Project contribution cap reached.");
        int removed = warehouse.withdraw(owner, project.material(),
                Math.min(allowed, project.target() - project.current()));
        if (removed <= 0) return Result.fail("No " + pretty(project.material()) + " in Warehouse.");
        int next = project.current() + removed;
        put(SERVER_SCOPE, "PROJECT", project.id(), "progress", String.valueOf(next));
        put(owner, "DAILY", day, "server_project_contribution", String.valueOf(used + removed));
        increment(owner, "SEASON", seasonId(), "server_project_total", removed);
        if (next >= project.target()) put(SERVER_SCOPE, "PROJECT", project.id(), "completed", "1");
        if (used < 256 && used + removed >= 256) {
            increment(owner, "SEASON", seasonId(), "chronicle_points", 2);
        }
        audit.log(owner, "SERVER_PROJECT", "{\"amount\":" + removed + ",\"season\":\""
                + safe(seasonId()) + "\"}");
        telemetry(owner, "SERVER_PROJECT_CONTRIBUTED", "{\"amount\":" + removed + "}");
        return Result.ok("Server Project +" + removed + " (" + next + "/" + project.target() + ").");
    }

    public Result prepareExpedition(UUID owner, NodeRecord node, String option) {
        if (warehouse == null || node == null || !node.getOwnerUuid().equals(owner)) {
            return Result.fail("Choose one of your Production Nodes.");
        }
        String normalized = option.toUpperCase(Locale.ROOT);
        if (!Set.of("SPEED", "QUANTITY", "RESEARCH").contains(normalized)) {
            return Result.fail("Preparation choice: speed, quantity, or research.");
        }
        String material = switch (node.getType()) {
            case MINING -> "COBBLESTONE";
            case FARMING -> "WHEAT";
            case WOODCUTTING -> "OAK_LOG";
            case LIVESTOCK -> "BEEF";
            case HUNTER -> "BONE";
            default -> "COBBLESTONE";
        };
        int cost = plugin.getConfig().getInt("exploration.preparation-kit-cost", 16);
        if (warehouse.getContents(owner).getOrDefault(material, 0) < cost) {
            return Result.fail("Preparation needs " + cost + " " + pretty(material) + " in Warehouse.");
        }
        warehouse.withdraw(owner, material, cost);
        put(owner, "NODE", String.valueOf(node.getId()), "next_preparation", normalized);
        return Result.ok("Prepared " + pretty(normalized) + " route for the next expedition.");
    }

    public String activatePreparation(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        String scope = String.valueOf(node.getId());
        String preparation = valueOr(get(owner, "NODE", scope, "next_preparation"), "NONE");
        put(owner, "NODE", scope, "next_preparation", "");
        put(owner, "NODE", scope, "active_preparation", preparation);
        return preparation;
    }

    public String finishPreparation(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        String scope = String.valueOf(node.getId());
        String preparation = valueOr(get(owner, "NODE", scope, "active_preparation"), "NONE");
        put(owner, "NODE", scope, "active_preparation", "");
        if ("RESEARCH".equals(preparation)) grantNodeExp(node, 150);
        return preparation;
    }

    public void cancelPreparation(NodeRecord node) {
        put(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "active_preparation", "");
    }

    private Project project(UUID owner, String id, String name, String material, int target) {
        int current = getInt(owner, "PROJECT", id, "progress", 0);
        boolean complete = "1".equals(get(owner, "PROJECT", id, "completed")) || current >= target;
        return new Project(id, name, material, current, target, complete);
    }

    // ---- Season and telemetry ------------------------------------------------

    public String seasonId() {
        return plugin.getConfig().getString("season.id", "preseason");
    }

    public int seasonWeek() {
        String startText = plugin.getConfig().getString("season.start-date", dayKey());
        try {
            LocalDate start = LocalDate.parse(startText);
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.now(GAME_ZONE));
            return (int) Math.max(1, Math.min(12, days / 7 + 1));
        } catch (Exception ignored) {
            return 1;
        }
    }

    public String seasonPhase() {
        int week = seasonWeek();
        if (week <= 2) return "Discovery";
        if (week <= 5) return "Development";
        if (week == 6) return "Midseason";
        if (week <= 9) return "Mastery";
        if (week <= 11) return "Finale";
        return "Celebration";
    }

    public void telemetry(UUID owner, String event, String detail) {
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

    public Map<String, Long> telemetrySummarySync() {
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

    // ---- Generic state helpers -----------------------------------------------

    private NodeRecord focusedRecord(UUID owner) {
        Long id = focusedNode(owner);
        if (id == null) return null;
        return nodeStore.getByOwner(owner).stream().filter(n -> n.getId() == id).findFirst().orElse(null);
    }

    private void grantNodeExp(NodeRecord node, long amount) {
        if (amount <= 0 || exploration == null) return;
        exploration.grantExplorationExp(node, amount);
        onNodeLevel(node);
        nodeStore.updateProduction(node);
    }

    private void addCoins(UUID owner, double amount) {
        PlayerData data = dataStore.getOnline(owner);
        if (data != null) data.addBalance(amount);
    }

    private boolean claimOnce(UUID owner, String scope, String scopeId, String key) {
        return stateStore.claimOnce(owner, scope, scopeId, key);
    }

    private long increment(UUID owner, String scope, String scopeId, String key, long amount) {
        return stateStore.increment(owner, scope, scopeId, key, amount);
    }

    private String get(UUID owner, String scope, String scopeId, String key) {
        return stateStore.get(owner, scope, scopeId, key);
    }

    private int getInt(UUID owner, String scope, String scopeId, String key, int fallback) {
        return stateStore.getInt(owner, scope, scopeId, key, fallback);
    }

    private long getLong(UUID owner, String scope, String scopeId, String key, long fallback) {
        return stateStore.getLong(owner, scope, scopeId, key, fallback);
    }

    private void put(UUID owner, String scope, String scopeId, String key, String value) {
        stateStore.put(owner, scope, scopeId, key, value);
    }

    private void persistCap(UUID owner, String material, String period, int amount) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_resource_caps (owner_uuid, material, period_key, amount) "
                                 + "VALUES (?, ?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setString(2, material);
                upsert.setString(3, period);
                upsert.setInt(4, amount);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist resource cap: " + e.getMessage());
            }
        });
    }

    private String dayKey() {
        return LocalDate.now(GAME_ZONE).toString();
    }

    private String weekKey() {
        LocalDate now = LocalDate.now(GAME_ZONE);
        WeekFields fields = WeekFields.of(DayOfWeek.MONDAY, 4);
        return now.get(fields.weekBasedYear()) + "-W" + String.format("%02d", now.get(fields.weekOfWeekBasedYear()));
    }

    private String discoveryKey(UUID owner, String type, String material) {
        return owner + "|" + type.toUpperCase(Locale.ROOT) + "|" + material.toUpperCase(Locale.ROOT);
    }

    private String capKey(UUID owner, String material, String period) {
        return owner + "|" + material.toUpperCase(Locale.ROOT) + "|" + period;
    }

    private boolean validBuildChoice(String tier, String choice, String branch) {
        return switch (tier) {
            case "specialization" -> Set.of("INDUSTRY", "DISCOVERY", "LOGISTICS").contains(choice);
            case "refinement" -> switch (branch) {
                case "INDUSTRY" -> Set.of("MASS_PRODUCTION", "FINE_PROCESSING").contains(choice);
                case "DISCOVERY" -> Set.of("DEEP_SURVEY", "LUCKY_ROUTE").contains(choice);
                case "LOGISTICS" -> Set.of("DEEP_STORAGE", "SMART_ROUTING").contains(choice);
                default -> false;
            };
            case "mastery" -> Set.of("FOREMAN", "PATHFINDER", "QUARTERMASTER").contains(choice);
            default -> false;
        };
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private String pretty(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return normalized.isEmpty() ? normalized
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String formatDuration(long millis) {
        long minutes = Math.max(1, millis / 60_000);
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : minutes + "m";
    }
}
