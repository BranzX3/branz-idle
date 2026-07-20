package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.GameDesignService.Project;
import dev.branzx.idlefarm.service.GameDesignService.Result;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import dev.branzx.idlefarm.storage.NodeStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Personal world-construction projects, the seasonal Server Project and
 * expedition preparation kits. Every flow that consumes Warehouse resources
 * settles the Warehouse row and the project state in one ordered transaction,
 * so a restart can never keep the progress while losing the payment (or the
 * reverse).
 */
public final class ProjectService {

    private static final UUID SERVER_SCOPE = new UUID(0, 0);

    /**
     * Optional hook fired when the Server Project reaches a new construction
     * stage. Injected so this service stays headless-testable; the plugin
     * wires it to a server-wide broadcast with a click action.
     */
    @FunctionalInterface
    public interface StageNotifier {
        void stageReached(String projectName, int stage, boolean completed);
    }

    private StageNotifier stageNotifier = (name, stage, completed) -> { };

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final GameStateStore state;
    private final AuditService audit;
    private final TelemetryService telemetry;
    private final SeasonService seasons;
    private final ChronicleService chronicle;
    private final SeasonalChronicleService seasonalChronicle;
    private final WarehouseService warehouse;
    private final NodeExpSink nodeExp;
    private final ProjectWorldService world;

    public ProjectService(IdleFarmPlugin plugin, Database database, GameStateStore state,
                          AuditService audit, TelemetryService telemetry, SeasonService seasons,
                          ChronicleService chronicle, SeasonalChronicleService seasonalChronicle,
                          WarehouseService warehouse,
                          NodeStore nodeStore, NodeExpSink nodeExp) {
        this.plugin = plugin;
        this.database = database;
        this.state = state;
        this.audit = audit;
        this.telemetry = telemetry;
        this.seasons = seasons;
        this.chronicle = chronicle;
        this.seasonalChronicle = seasonalChronicle;
        this.warehouse = warehouse;
        this.nodeExp = nodeExp;
        this.world = new ProjectWorldService(plugin, nodeStore, this::projects, this::serverProject);
    }

    public void setStageNotifier(StageNotifier notifier) {
        if (notifier != null) {
            this.stageNotifier = notifier;
        }
    }

    public void startWorldRendering() {
        world.start();
    }

    public void onResidentialRemoved(UUID owner) {
        world.relocateAfterResidentialRemoved(owner);
    }

    public List<Project> projects(UUID owner) {
        return List.of(
                project(owner, "storehouse", "Expanded Storehouse", "OAK_LOG", 512),
                project(owner, "expedition_dock", "Expedition Dock", "WOOL", 384),
                project(owner, "chronicle_hall", "Chronicle Hall", "STONE", 1_024));
    }

    public Result contributeProject(UUID owner, String id, int requested) {
        Project project = projects(owner).stream().filter(p -> p.id().equalsIgnoreCase(id))
                .findFirst().orElse(null);
        if (project == null) return Result.fail("Unknown project.");
        if (project.completed()) return Result.fail("Project is already complete.");
        int remaining = project.target() - project.current();
        WarehouseService.Snapshot before = warehouse.snapshot(owner);
        int removed = warehouse.prepareWithdraw(owner, project.material(),
                Math.min(Math.max(1, requested), remaining));
        if (removed <= 0) return Result.fail("No " + DesignText.pretty(project.material()) + " in Warehouse.");
        int next = project.current() + removed;
        List<GameStateStore.Row> rows = new ArrayList<>();
        rows.add(state.prepare(owner, "PROJECT", project.id(), "progress", String.valueOf(next)));
        if (next >= project.target()) {
            rows.add(state.prepare(owner, "PROJECT", project.id(), "completed", "1"));
            rows.add(chronicle.stagePointsGain(owner, 5));
        }
        if (!commitWithWarehouse("project contribution " + project.id(),
                before, warehouse.snapshot(owner), rows)) {
            return Result.fail("Project settlement failed; no resources were consumed.");
        }
        Project updated = new Project(project.id(), project.name(), project.material(),
                next, project.target(), next >= project.target());
        if (ProjectWorldService.constructionStage(updated)
                != ProjectWorldService.constructionStage(project)) {
            world.render(owner, project.id(), ProjectWorldService.constructionStage(updated));
        }
        audit.log(owner, "PROJECT_CONTRIBUTE", "{\"id\":\"" + project.id() + "\",\"amount\":" + removed + "}");
        telemetry.record(owner, "PROJECT_CONTRIBUTED", "{\"id\":\"" + project.id() + "\",\"amount\":" + removed + "}");
        seasonalChronicle.advance(owner, "project", removed);
        return Result.ok("Contributed " + removed + " " + DesignText.pretty(project.material()) + " ("
                + next + "/" + project.target() + ").");
    }

    public Project serverProject() {
        int target = plugin.getConfig().getInt("projects.server.target", 100_000);
        String material = plugin.getConfig().getString("projects.server.material", "COBBLESTONE");
        return project(SERVER_SCOPE, "season_" + seasons.id(), "Season Community Monument",
                material, target);
    }

    public Result contributeServerProject(UUID owner, int requested) {
        Project project = serverProject();
        String day = GameClock.dayKey();
        int dailyCap = plugin.getConfig().getInt("projects.server.daily-cap", 1_024);
        int used = state.getInt(owner, "DAILY", day, "server_project_contribution", 0);
        int allowed = Math.min(Math.max(0, requested), Math.max(0, dailyCap - used));
        if (allowed <= 0) return Result.fail("Daily Server Project contribution cap reached.");
        WarehouseService.Snapshot before = warehouse.snapshot(owner);
        int removed = warehouse.prepareWithdraw(owner, project.material(),
                Math.min(allowed, project.target() - project.current()));
        if (removed <= 0) return Result.fail("No " + DesignText.pretty(project.material()) + " in Warehouse.");
        int next = project.current() + removed;
        List<GameStateStore.Row> rows = new ArrayList<>();
        rows.add(state.prepare(SERVER_SCOPE, "PROJECT", project.id(), "progress", String.valueOf(next)));
        rows.add(state.prepare(owner, "DAILY", day, "server_project_contribution",
                String.valueOf(used + removed)));
        rows.add(state.prepareIncrement(owner, "SEASON", seasons.id(), "server_project_total", removed));
        if (next >= project.target()) {
            rows.add(state.prepare(SERVER_SCOPE, "PROJECT", project.id(), "completed", "1"));
        }
        if (used < 256 && used + removed >= 256) {
            rows.add(chronicle.stageSeasonalPointsGain(owner, 2));
        }
        if (!commitWithWarehouse("server project contribution",
                before, warehouse.snapshot(owner), rows)) {
            return Result.fail("Server Project settlement failed; no resources were consumed.");
        }
        Project updated = new Project(project.id(), project.name(), project.material(),
                next, project.target(), next >= project.target());
        if (ProjectWorldService.constructionStage(updated)
                != ProjectWorldService.constructionStage(project)) {
            world.renderServer(updated);
            stageNotifier.stageReached(updated.name(),
                    ProjectWorldService.constructionStage(updated), updated.completed());
        }
        audit.log(owner, "SERVER_PROJECT", "{\"amount\":" + removed + ",\"season\":\""
                + DesignText.safe(seasons.id()) + "\"}");
        telemetry.record(owner, "SERVER_PROJECT_CONTRIBUTED", "{\"amount\":" + removed + "}");
        seasonalChronicle.advance(owner, "server_project", removed);
        return Result.ok("Server Project +" + removed + " (" + next + "/" + project.target() + ").");
    }

    // ---- expedition preparation kits ------------------------------------------

    public Result prepareExpedition(UUID owner, NodeRecord node, String option) {
        if (node == null || !node.getOwnerUuid().equals(owner)) {
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
        if ("SUPPLY_SHORTAGE".equals(seasons.modifier())) {
            cost = Math.max(1, (int) Math.ceil(cost * 0.75));
        }
        if (warehouse.getContents(owner).getOrDefault(material, 0) < cost) {
            return Result.fail("Preparation needs " + cost + " " + DesignText.pretty(material) + " in Warehouse.");
        }
        WarehouseService.Snapshot before = warehouse.snapshot(owner);
        warehouse.prepareWithdraw(owner, material, cost);
        GameStateStore.Row row = state.prepare(owner, "NODE", String.valueOf(node.getId()),
                "next_preparation", normalized);
        if (!commitWithWarehouse("expedition preparation",
                before, warehouse.snapshot(owner), List.of(row))) {
            return Result.fail("Preparation settlement failed; no resources were consumed.");
        }
        return Result.ok("Prepared " + DesignText.pretty(normalized) + " route for the next expedition.");
    }

    public String activatePreparation(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        String scope = String.valueOf(node.getId());
        String preparation = DesignText.valueOr(state.get(owner, "NODE", scope, "next_preparation"), "NONE");
        state.put(owner, "NODE", scope, "next_preparation", "");
        state.put(owner, "NODE", scope, "active_preparation", preparation);
        return preparation;
    }

    public String finishPreparation(NodeRecord node) {
        UUID owner = node.getOwnerUuid();
        String scope = String.valueOf(node.getId());
        String preparation = DesignText.valueOr(state.get(owner, "NODE", scope, "active_preparation"), "NONE");
        state.put(owner, "NODE", scope, "active_preparation", "");
        if ("RESEARCH".equals(preparation)) nodeExp.grant(node, 150);
        return preparation;
    }

    public void cancelPreparation(NodeRecord node) {
        state.put(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()), "active_preparation", "");
    }

    private boolean commitWithWarehouse(String operation, WarehouseService.Snapshot before,
                                        WarehouseService.Snapshot snapshot,
                                        List<GameStateStore.Row> rows) {
        boolean committed = database.executeTransaction(operation, connection -> {
            WarehouseService.write(connection, snapshot);
            for (GameStateStore.Row row : rows) {
                GameStateStore.write(connection, row);
            }
        });
        if (!committed) {
            warehouse.restore(before);
            return false;
        }
        rows.forEach(state::applyCommitted);
        return true;
    }

    private Project project(UUID owner, String id, String name, String material, int target) {
        int current = state.getInt(owner, "PROJECT", id, "progress", 0);
        boolean complete = "1".equals(state.get(owner, "PROJECT", id, "completed")) || current >= target;
        return new Project(id, name, material, current, target, complete);
    }
}
