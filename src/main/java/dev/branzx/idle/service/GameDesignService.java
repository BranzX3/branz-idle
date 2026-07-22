package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.command.CommandLinks;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.design.ChronicleService;
import dev.branzx.idle.service.design.CommissionService;
import dev.branzx.idle.service.design.DiscoveryService;
import dev.branzx.idle.service.design.FeatureControlService;
import dev.branzx.idle.service.design.FocusService;
import dev.branzx.idle.service.design.EconomyWatchService;
import dev.branzx.idle.service.design.FrontierService;
import dev.branzx.idle.service.design.NodeBuildService;
import dev.branzx.idle.service.design.ProjectService;
import dev.branzx.idle.service.design.SeasonService;
import dev.branzx.idle.service.design.SeasonalChronicleService;
import dev.branzx.idle.service.design.TelemetryService;
import dev.branzx.idle.service.design.WorkerMetaService;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.GameStateStore;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import dev.branzx.idle.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Facade over the typed game-design services. Delivery code talks to this
 * class; each concern (focus, commissions, Chronicle, discoveries, node
 * builds, projects, seasons, telemetry) lives in its own service under
 * {@code service.design}, all sharing one scoped {@link GameStateStore}.
 *
 * <p>The facade itself only routes gameplay events to the interested
 * services and owns the two cross-cutting reward sinks (node EXP and Coins),
 * which is where the deliberate ExplorationService cycle is broken.</p>
 */
public final class GameDesignService {

    public record Result(boolean success, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    public record Commission(String id, String description, int current, int target,
                             String reward, boolean claimed) {
    }

    public record Achievement(String id, String category, String name, String description,
                              int points, boolean completed, boolean claimed) {
    }

    public record Project(String id, String name, String material, int current, int target,
                          boolean completed) {
    }

    private final IdlePlugin plugin;
    private final NodeStore nodeStore;
    private final PlayerDataStore dataStore;
    private final GameStateStore stateStore;
    private final ProgressionRewards rewards;
    private final ExplorationService exploration;

    private final TelemetryService telemetryService;
    private EconomyWatchService economyWatch;
    private final FeatureControlService controls;
    private final SeasonService seasons;
    private final ChronicleService chronicle;
    private final SeasonalChronicleService seasonalChronicle;
    private final DiscoveryService discovery;
    private final FocusService focus;
    private final NodeBuildService builds;
    private final ProjectService projects;
    private final WorkerMetaService workerMeta;
    private final CommissionService commissions;
    private final FrontierService frontier;

    public GameDesignService(IdlePlugin plugin, Database database, NodeStore nodeStore,
                             PlayerDataStore dataStore, AuditService audit,
                             WarehouseService warehouse, ExplorationService exploration) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.dataStore = dataStore;
        this.exploration = exploration;
        this.stateStore = new GameStateStore(plugin, database);
        this.rewards = ProgressionRewards.from(plugin);
        this.telemetryService = new TelemetryService(plugin, database);
        this.controls = new FeatureControlService(plugin);
        this.seasons = new SeasonService(plugin);
        this.chronicle = new ChronicleService(plugin, stateStore, audit, telemetryService,
                seasons, this::addCoins);
        this.seasonalChronicle = new SeasonalChronicleService(plugin, stateStore, seasons,
                controls, chronicle, telemetryService);
        this.discovery = new DiscoveryService(plugin, database, telemetryService);
        this.focus = new FocusService(stateStore, nodeStore, audit, telemetryService);
        this.builds = new NodeBuildService(database, stateStore, dataStore, audit, seasons);
        this.projects = new ProjectService(plugin, database, stateStore, audit, telemetryService,
                seasons, chronicle, seasonalChronicle, warehouse, nodeStore, this::grantNodeExp);
        this.workerMeta = new WorkerMetaService(stateStore, nodeStore, projects);
        this.commissions = new CommissionService(plugin, database, stateStore, audit, telemetryService,
                rewards, focus, builds, chronicle, seasonalChronicle, warehouse,
                this::grantNodeExp, this::addCoins);
        this.frontier = new FrontierService(plugin, stateStore, warehouse, controls, telemetryService);
        wireChatNotifiers();
    }

    /** Progress moments become one chat line with a click action each. */
    private void wireChatNotifiers() {
        commissions.setCompletionNotifier((owner, message, label, command) -> {
            Player player = Bukkit.getPlayer(owner);
            if (player != null) {
                player.sendMessage(Component.text()
                        .append(Component.text("[Progress] ", NamedTextColor.GOLD))
                        .append(Component.text(message + " ", NamedTextColor.GREEN))
                        .append(CommandLinks.run(label, command))
                        .build());
            }
        });
        projects.setStageNotifier((name, stage, completed) ->
                Bukkit.broadcast(Component.text()
                        .append(Component.text("[Project] ", NamedTextColor.GOLD))
                        .append(Component.text(name
                                + (completed ? " is complete!" : " reached construction stage "
                                + stage + "!") + " ", NamedTextColor.GREEN))
                        .append(CommandLinks.run("[View]", "/idle projects"))
                        .build()));
        seasonalChronicle.setArchiveNotifier((owner, participation) -> {
            Player player = Bukkit.getPlayer(owner);
            if (player == null) return;
            player.sendMessage(Component.text()
                    .append(Component.text("[Chronicle] ", NamedTextColor.GOLD))
                    .append(Component.text("Season " + participation.seasonId()
                            + " closed: " + participation.points() + " Seasonal Points, "
                            + participation.objectives() + " objectives. ", NamedTextColor.GREEN))
                    .append(CommandLinks.run("[Open Chronicle]", "/idle chronicle"))
                    .build());
        });
    }

    /**
     * Starts the periodic economy threshold sweep. The dashboard query is
     * blocking, so the timer runs off the main thread; the alert lines are
     * sent from that thread too, which Adventure allows.
     */
    private void startEconomyWatch() {
        if (!plugin.getConfig().getBoolean("economy.alerts.broadcast", true)) return;
        this.economyWatch = new EconomyWatchService(
                () -> telemetryService.economyDashboardSync().alerts(),
                new EconomyWatchService.AlertSink() {
                    @Override
                    public void raised(TelemetryService.AdminAlert alert) {
                        AdminAlerts.broadcast("idle.admin.metrics", Component.text()
                                .append(Component.text("[Economy] ",
                                        alert.severity() == TelemetryService.Severity.CRITICAL
                                                ? NamedTextColor.RED : NamedTextColor.GOLD))
                                .append(Component.text(alert.message() + " ", NamedTextColor.YELLOW))
                                .append(CommandLinks.run("[Open Metrics]", "/idle admin metrics"))
                                .build());
                    }

                    @Override
                    public void cleared(TelemetryService.AdminAlert alert) {
                        AdminAlerts.broadcast("idle.admin.metrics", Component.text()
                                .append(Component.text("[Economy] ", NamedTextColor.GRAY))
                                .append(Component.text(alert.code()
                                        + " is back inside its threshold. ", NamedTextColor.GRAY))
                                .append(CommandLinks.run("[Open Metrics]", "/idle admin metrics"))
                                .build());
                    }
                });
        long minutes = Math.max(1, plugin.getConfig().getLong("economy.alerts.sweep-minutes", 15));
        long ticks = minutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, economyWatch::sweep, 600L, ticks);
    }

    public ProgressionRewards progressionRewards() {
        return rewards;
    }

    public void loadAllSync() {
        chronicle.loadDefinitions();
        seasonalChronicle.loadDefinitions();
        try {
            stateStore.loadAllSync();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load scoped game state: " + e.getMessage());
        }
        discovery.loadSync();
        projects.startWorldRendering();
        startEconomyWatch();
    }

    // ---- Focus and onboarding -------------------------------------------------

    public Long focusedNode(UUID owner) {
        return focus.focusedNode(owner);
    }

    public boolean isFocused(NodeRecord node) {
        return focus.isFocused(node);
    }

    public Result setFocus(UUID owner, NodeRecord node, boolean freeOverride) {
        return focus.setFocus(owner, node, freeOverride);
    }

    public boolean firstResidentialIsFree(UUID owner) {
        return focus.firstResidentialIsFree(owner);
    }

    public boolean firstProductionIsFree(UUID owner) {
        return focus.firstProductionIsFree(owner);
    }

    public void onLogin(UUID owner) {
        commissions.onLogin(owner);
        seasonalChronicle.archiveFinishedSeason(owner);
    }

    /** Called after an authoritative claim. Returns true for the first production node. */
    public boolean onNodeClaimed(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        if (node.getType() == NodeType.RESIDENTIAL) {
            chronicle.complete(owner, "place_to_begin");
            telemetry(owner, "FIRST_RESIDENTIAL", "{\"node\":" + node.getId() + "}");
            return false;
        }
        boolean first = !focus.isStarterProductionClaimed(owner);
        focus.incrementProductionType(node);
        chronicle.countMax(owner, "nodes_owned_max", nodeStore.getByOwner(owner).size());
        chronicle.countMax(owner, "production_types_distinct", nodeStore.getByOwner(owner).stream()
                .filter(owned -> owned.getType().isProduction())
                .map(NodeRecord::getType).distinct().count());
        if (first) {
            focus.markStarterProduction(owner);
            focus.setFocus(owner, node, true);
            grantNodeExp(node, rewards.starterNodeExp());
            if (exploration != null) {
                exploration.adminSpawn(node, "first_survey");
            }
            chronicle.complete(owner, "first_camp");
            telemetry(owner, "FIRST_PRODUCTION", "{\"node\":" + node.getId() + "}");
        }
        return first;
    }

    // ---- Worker metadata ------------------------------------------------------

    public void markStarterWorker(UUID owner, UUID worker) {
        workerMeta.markStarterWorker(owner, worker);
    }

    public boolean isStarterWorker(UUID worker) {
        return workerMeta.isStarterWorker(worker);
    }

    public boolean toggleWorkerLock(UUID owner, UUID worker) {
        return workerMeta.toggleWorkerLock(owner, worker);
    }

    public boolean isWorkerLocked(UUID worker) {
        return workerMeta.isWorkerLocked(worker);
    }

    public String workerCharm(UUID owner, UUID worker) {
        return workerMeta.workerCharm(owner, worker);
    }

    public String workerCharm(UUID worker) {
        return workerMeta.workerCharm(worker);
    }

    public Result equipCharm(UUID owner, WorkerRecord worker, String charm) {
        return workerMeta.equipCharm(owner, worker, charm);
    }

    public long trainingNotes(UUID owner) {
        return workerMeta.trainingNotes(owner);
    }

    public void addTrainingNotes(UUID owner, long amount) {
        workerMeta.addTrainingNotes(owner, amount);
    }

    public long takeTrainingNotes(UUID owner, long requested) {
        return workerMeta.takeTrainingNotes(owner, requested);
    }

    // ---- Reward cadence and commissions --------------------------------------

    public void onWorkerAssigned(NodeRecord node, int crewSize) {
        UUID owner = node.getOwnerUuid();
        chronicle.complete(owner, "first_shift");
        chronicle.count(owner, "workers_assigned_total", 1);
        if (crewSize >= node.getTier()) {
            chronicle.complete(owner, "growing_crew");
        }
        commissions.advance(node, "crew", 1);
        telemetry(owner, "WORKER_ASSIGNED", "{\"node\":" + node.getId() + "}");
    }

    public void onBufferCollected(NodeRecord node, int amount) {
        if (amount <= 0) return;
        commissions.onBufferCollected(node, amount);
        chronicle.complete(node.getOwnerUuid(), "supplies_arrive");
        seasonalChronicle.advance(node.getOwnerUuid(), "collect", amount);
        telemetry(node.getOwnerUuid(), "BUFFER_COLLECTED",
                "{\"node\":" + node.getId() + ",\"amount\":" + amount + "}");
    }

    public void onItemsProduced(NodeRecord node, Map<String, Integer> produced) {
        int total = produced.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return;
        UUID owner = node.getOwnerUuid();
        commissions.onItemsProduced(node, total);
        for (Map.Entry<String, Integer> entry : produced.entrySet()) {
            discover(owner, node.getType(), entry.getKey(), entry.getValue());
        }
        chronicle.count(owner, "produced_total", total);
        chronicle.count(owner, "produced_" + node.getType().name(), total);
        seasonalChronicle.advance(owner, "produce", total);
        telemetry(owner, "ITEM_PRODUCED",
                "{\"node\":" + node.getId() + ",\"amount\":" + total + "}");
    }

    public void onExplorationClaimed(NodeRecord node, String grade, int teamSize) {
        UUID owner = node.getOwnerUuid();
        commissions.onExplorationClaimed(node, grade);
        chronicle.complete(owner, "beyond_fence");
        chronicle.count(owner, "events_total", 1);
        chronicle.count(owner, "events_" + node.getType().name(), 1);
        chronicle.countMax(owner, "expedition_team_max", teamSize);
        if ("GREAT".equals(grade) || "JACKPOT".equals(grade)) {
            chronicle.count(owner, "great_total", 1);
            chronicle.count(owner, "great_" + node.getType().name(), 1);
        }
        if ("JACKPOT".equals(grade)) {
            chronicle.count(owner, "jackpot_total", 1);
        }
        seasonalChronicle.advance(owner, "exploration", 1);
        telemetry(owner, "EXPLORATION_COMPLETED",
                "{\"node\":" + node.getId() + ",\"grade\":\""
                        + dev.branzx.idle.service.design.DesignText.safe(grade) + "\"}");
    }

    /** Manual Warehouse withdrawals only, not settlement-driven consumption. */
    public void onWarehouseWithdrawn(UUID owner) {
        chronicle.count(owner, "warehouse_withdrawals", 1);
    }

    public void onWorkerFused(UUID owner) {
        chronicle.count(owner, "fuse_success_total", 1);
    }

    public void onTradeCompleted(UUID owner) {
        chronicle.count(owner, "trades_completed", 1);
    }

    /** Counts distinct weeks with at least one Global Expedition commitment. */
    public void onGlobalExpeditionCommitted(UUID owner) {
        if (stateStore.claimOnce(owner, "WEEKLY",
                dev.branzx.idle.service.design.GameClock.weekKey(), "global_commit")) {
            chronicle.count(owner, "global_expedition_weeks", 1);
        }
        seasonalChronicle.advance(owner, "expedition_commit", 1);
    }

    public List<Commission> commissions(UUID owner) {
        return commissions.commissions(owner);
    }

    public Result claimCommission(UUID owner, String slot) {
        return commissions.claimCommission(owner, slot);
    }

    public Result rerollCommission(UUID owner, String slot) {
        return commissions.reroll(owner, slot);
    }

    public Result claimWeeklyChapter(UUID owner) {
        return commissions.claimWeeklyChapter(owner);
    }

    // ---- Discoveries and rare-resource caps ----------------------------------

    public boolean allowResource(UUID owner, String material, int nodeLevel) {
        return discovery.allowResource(owner, material, nodeLevel);
    }

    public FrontierService.Profession frontierProfession(UUID owner, NodeType type) {
        return frontier.profession(owner, type);
    }

    public FrontierService.Equipment frontierEquipment(UUID owner, NodeRecord node) {
        return frontier.equipment(owner, node);
    }

    public List<FrontierService.Recipe> frontierRecipes(NodeType type) {
        return frontier.recipes(type);
    }

    public Result craftFrontierEquipment(UUID owner, NodeRecord node, int tier) {
        return frontier.craft(owner, node, tier);
    }

    public Result trainFrontierProfession(UUID owner, NodeType type, String material, int amount) {
        return frontier.train(owner, type, material, amount);
    }

    public Result repairFrontierEquipment(UUID owner, NodeRecord node) {
        return frontier.repair(owner, node);
    }

    public boolean frontierEnabled(UUID owner) {
        return frontier.enabled(owner);
    }

    public void consumeFrontierDurability(NodeRecord node, int produced) {
        frontier.consumeDurability(node, produced);
    }

    public Map<String, Long> discoveries(UUID owner, NodeType type) {
        return discovery.discoveries(owner, type);
    }

    public void discover(UUID owner, NodeType type, String material, int amount) {
        if (discovery.discover(owner, type, material, amount)) {
            chronicle.count(owner, "unique_discoveries", 1);
            chronicle.count(owner, "discoveries_" + type.name(), 1);
        }
    }

    // ---- Chronicle -----------------------------------------------------------

    public List<Achievement> achievements(UUID owner) {
        return chronicle.achievements(owner);
    }

    public int chroniclePoints(UUID owner) {
        return chronicle.points(owner);
    }

    public int seasonalChroniclePoints(UUID owner) {
        return chronicle.seasonalPoints(owner);
    }

    public Result claimAchievement(UUID owner, String id) {
        return chronicle.claim(owner, id);
    }

    public List<SeasonalChronicleService.Objective> seasonalObjectives(UUID owner) {
        return seasonalChronicle.objectives(owner);
    }

    public Result claimSeasonalObjective(UUID owner, String id) {
        return seasonalChronicle.claim(owner, id);
    }

    public List<SeasonalChronicleService.RewardTier> seasonalRewardTrack(UUID owner) {
        return seasonalChronicle.rewardTrack(owner);
    }

    public Result claimSeasonalReward(UUID owner, String id) {
        return seasonalChronicle.claimReward(owner, id);
    }

    /** Finished seasons this player took part in, for the permanent Chronicle. */
    public List<SeasonalChronicleService.Participation> seasonParticipation(UUID owner) {
        return seasonalChronicle.participation(owner);
    }

    public void onNodeLevel(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        chronicle.countMax(owner, "node_level_" + node.getType().name(), node.getExplorationLevel());
        chronicle.countMax(owner, "node_level_max", node.getExplorationLevel());
        if (node.getExplorationLevel() >= 100) {
            builds.markFrontierEligible(node);
            chronicle.complete(owner, "node_master_100");
            telemetry(owner, "NODE_LEVEL_100", "{\"node\":" + node.getId() + "}");
        }
    }

    // ---- Node specialization and type perks ---------------------------------

    public String specialization(NodeRecord node) {
        return builds.specialization(node);
    }

    public String refinement(NodeRecord node) {
        return builds.refinement(node);
    }

    public String mastery(NodeRecord node) {
        return builds.mastery(node);
    }

    public Result selectBuild(UUID owner, NodeRecord node, String tier, String choice) {
        return builds.selectBuild(owner, node, tier, choice);
    }

    public Result selectTypePerk(UUID owner, NodeRecord node, int level, String choice) {
        return builds.selectTypePerk(owner, node, level, choice);
    }

    public List<String> typePerkChoices(NodeType type, int level) {
        return builds.typePerkChoices(type, level);
    }

    public String typePerk(NodeRecord node, int level) {
        return builds.typePerk(node, level);
    }

    public double productionMultiplier(NodeRecord node) {
        return builds.productionMultiplier(node) * frontier.productionMultiplier(node);
    }

    public double bufferMultiplier(NodeRecord node) {
        return builds.bufferMultiplier(node);
    }

    public double fullBufferResearchMultiplier(NodeRecord node) {
        return builds.fullBufferResearchMultiplier(node);
    }

    public double eventExpMultiplier(NodeRecord node) {
        return builds.eventExpMultiplier(node);
    }

    public double resourceWeightMultiplier(NodeRecord node, String material) {
        return builds.resourceWeightMultiplier(node, material);
    }

    // ---- Projects and expedition preparation ---------------------------------

    public List<Project> projects(UUID owner) {
        return projects.projects(owner);
    }

    public Result contributeProject(UUID owner, String id, int requested) {
        return projects.contributeProject(owner, id, requested);
    }

    public void onResidentialRemoved(UUID owner) {
        projects.onResidentialRemoved(owner);
    }

    public Project serverProject() {
        return projects.serverProject();
    }

    public Result contributeServerProject(UUID owner, int requested) {
        return projects.contributeServerProject(owner, requested);
    }

    public Result prepareExpedition(UUID owner, NodeRecord node, String option) {
        return projects.prepareExpedition(owner, node, option);
    }

    public String activatePreparation(NodeRecord node) {
        return projects.activatePreparation(node);
    }

    public String finishPreparation(NodeRecord node) {
        return projects.finishPreparation(node);
    }

    public void cancelPreparation(NodeRecord node) {
        projects.cancelPreparation(node);
    }

    // ---- Season and telemetry ------------------------------------------------

    public String seasonId() {
        return seasons.id();
    }

    public int seasonWeek() {
        return seasons.week();
    }

    public String seasonPhase() {
        return seasons.phase();
    }

    public String seasonModifier() {
        return seasons.modifier();
    }

    public SeasonService.WeekSchedule seasonSchedule() {
        return seasons.schedule();
    }

    public List<String> seasonValidationErrors() {
        return seasons.validationErrors();
    }

    public boolean featureEnabled(String key, UUID owner) {
        return controls.enabled(key, owner);
    }

    public String experimentVariant(String key, UUID owner, String fallback) {
        return controls.variant(key, owner, fallback);
    }

    public void telemetry(UUID owner, String event, String detail) {
        telemetryService.record(owner, event, detail);
    }

    public Map<String, Long> telemetrySummarySync() {
        return telemetryService.summarySync();
    }

    public dev.branzx.idle.service.design.TelemetryService.EconomyDashboard
    economyDashboardSync() {
        return telemetryService.economyDashboardSync();
    }

    // ---- Reward sinks --------------------------------------------------------

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
}
