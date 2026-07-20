package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.design.ChronicleService;
import dev.branzx.idlefarm.service.design.CommissionService;
import dev.branzx.idlefarm.service.design.DiscoveryService;
import dev.branzx.idlefarm.service.design.FocusService;
import dev.branzx.idlefarm.service.design.NodeBuildService;
import dev.branzx.idlefarm.service.design.ProjectService;
import dev.branzx.idlefarm.service.design.SeasonService;
import dev.branzx.idlefarm.service.design.TelemetryService;
import dev.branzx.idlefarm.service.design.WorkerMetaService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.worker.WorkerRecord;

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

    public record Achievement(String id, String name, String description, int points,
                              boolean completed, boolean claimed) {
    }

    public record Project(String id, String name, String material, int current, int target,
                          boolean completed) {
    }

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final PlayerDataStore dataStore;
    private final GameStateStore stateStore;
    private final ProgressionRewards rewards;
    private final ExplorationService exploration;

    private final TelemetryService telemetryService;
    private final SeasonService seasons;
    private final ChronicleService chronicle;
    private final DiscoveryService discovery;
    private final FocusService focus;
    private final NodeBuildService builds;
    private final ProjectService projects;
    private final WorkerMetaService workerMeta;
    private final CommissionService commissions;

    public GameDesignService(IdleFarmPlugin plugin, Database database, NodeStore nodeStore,
                             PlayerDataStore dataStore, AuditService audit,
                             WarehouseService warehouse, ExplorationService exploration) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.dataStore = dataStore;
        this.exploration = exploration;
        this.stateStore = new GameStateStore(plugin, database);
        this.rewards = ProgressionRewards.from(plugin);
        this.telemetryService = new TelemetryService(plugin, database);
        this.seasons = new SeasonService(plugin);
        this.chronicle = new ChronicleService(plugin, stateStore, audit, telemetryService,
                seasons, this::addCoins);
        this.discovery = new DiscoveryService(plugin, database, stateStore, telemetryService);
        this.focus = new FocusService(stateStore, nodeStore, audit, telemetryService);
        this.builds = new NodeBuildService(database, stateStore, dataStore, audit, seasons);
        this.projects = new ProjectService(plugin, database, stateStore, audit, telemetryService,
                seasons, chronicle, warehouse, this::grantNodeExp);
        this.workerMeta = new WorkerMetaService(stateStore, nodeStore, projects);
        this.commissions = new CommissionService(database, stateStore, audit, telemetryService,
                rewards, focus, builds, chronicle, warehouse, this::grantNodeExp, this::addCoins);
    }

    public ProgressionRewards progressionRewards() {
        return rewards;
    }

    public void loadAllSync() {
        chronicle.loadDefinitions();
        try {
            stateStore.loadAllSync();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load scoped game state: " + e.getMessage());
        }
        discovery.loadSync();
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

    public void onWorkerAssigned(NodeRecord node) {
        chronicle.complete(node.getOwnerUuid(), "first_shift");
        commissions.advance(node, "crew", 1);
        telemetry(node.getOwnerUuid(), "WORKER_ASSIGNED", "{\"node\":" + node.getId() + "}");
    }

    public void onBufferCollected(NodeRecord node, int amount) {
        if (amount <= 0) return;
        commissions.onBufferCollected(node, amount);
        chronicle.complete(node.getOwnerUuid(), "supplies_arrive");
        telemetry(node.getOwnerUuid(), "BUFFER_COLLECTED",
                "{\"node\":" + node.getId() + ",\"amount\":" + amount + "}");
    }

    public void onItemsProduced(NodeRecord node, Map<String, Integer> produced) {
        int total = produced.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) return;
        UUID owner = node.getOwnerUuid();
        commissions.onItemsProduced(node, total);
        for (Map.Entry<String, Integer> entry : produced.entrySet()) {
            discovery.discover(owner, node.getType(), entry.getKey(), entry.getValue());
        }
        telemetry(owner, "ITEM_PRODUCED",
                "{\"node\":" + node.getId() + ",\"amount\":" + total + "}");
    }

    public void onExplorationClaimed(NodeRecord node, String grade) {
        commissions.onExplorationClaimed(node, grade);
        chronicle.complete(node.getOwnerUuid(), "beyond_fence");
        telemetry(node.getOwnerUuid(), "EXPLORATION_COMPLETED",
                "{\"node\":" + node.getId() + ",\"grade\":\""
                        + dev.branzx.idlefarm.service.design.DesignText.safe(grade) + "\"}");
    }

    public List<Commission> commissions(UUID owner) {
        return commissions.commissions(owner);
    }

    public Result claimCommission(UUID owner, String slot) {
        return commissions.claimCommission(owner, slot);
    }

    public Result claimWeeklyChapter(UUID owner) {
        return commissions.claimWeeklyChapter(owner);
    }

    // ---- Discoveries and rare-resource caps ----------------------------------

    public boolean allowResource(UUID owner, String material, int nodeLevel) {
        return discovery.allowResource(owner, material, nodeLevel);
    }

    public Map<String, Long> discoveries(UUID owner, NodeType type) {
        return discovery.discoveries(owner, type);
    }

    public void discover(UUID owner, NodeType type, String material, int amount) {
        discovery.discover(owner, type, material, amount);
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

    public void onNodeLevel(NodeRecord node) {
        if (node.getExplorationLevel() >= 100) {
            builds.markFrontierEligible(node);
            chronicle.complete(node.getOwnerUuid(), "node_master_100");
            telemetry(node.getOwnerUuid(), "NODE_LEVEL_100", "{\"node\":" + node.getId() + "}");
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
        return builds.productionMultiplier(node);
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

    public void telemetry(UUID owner, String event, String detail) {
        telemetryService.record(owner, event, detail);
    }

    public Map<String, Long> telemetrySummarySync() {
        return telemetryService.summarySync();
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
