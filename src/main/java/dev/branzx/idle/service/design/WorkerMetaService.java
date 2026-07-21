package dev.branzx.idle.service.design;

import dev.branzx.idle.service.GameDesignService.Project;
import dev.branzx.idle.service.GameDesignService.Result;
import dev.branzx.idle.storage.GameStateStore;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.worker.WorkerRecord;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Per-worker account metadata: the bound Starter Worker, favorite locks,
 * equipped Charms and the Training Notes wallet.
 */
public final class WorkerMetaService {

    private static final Set<String> CHARMS =
            Set.of("NONE", "SURVEY_COMPASS", "HEAVY_GLOVES", "LUCKY_TOKEN", "TRAIL_BOOTS");

    private final GameStateStore state;
    private final NodeStore nodeStore;
    private final ProjectService projects;

    public WorkerMetaService(GameStateStore state, NodeStore nodeStore, ProjectService projects) {
        this.state = state;
        this.nodeStore = nodeStore;
        this.projects = projects;
    }

    public void markStarterWorker(UUID owner, UUID worker) {
        state.put(owner, "ACCOUNT", "-", "starter_worker", worker.toString());
    }

    public boolean isStarterWorker(UUID worker) {
        if (worker == null) return false;
        return state.containsValue("ACCOUNT", "starter_worker", worker.toString());
    }

    public boolean toggleWorkerLock(UUID owner, UUID worker) {
        String current = state.get(owner, "WORKER", worker.toString(), "locked");
        boolean next = !"1".equals(current);
        state.put(owner, "WORKER", worker.toString(), "locked", next ? "1" : "0");
        return next;
    }

    public boolean isWorkerLocked(UUID worker) {
        if (worker == null) return false;
        return "1".equals(state.findByScopeId("WORKER", worker.toString(), "locked"));
    }

    public String workerCharm(UUID owner, UUID worker) {
        return DesignText.valueOr(state.get(owner, "WORKER", worker.toString(), "charm"), "NONE");
    }

    public String workerCharm(UUID worker) {
        if (worker == null) return "NONE";
        return DesignText.valueOr(state.findByScopeId("WORKER", worker.toString(), "charm"), "NONE");
    }

    public Result equipCharm(UUID owner, WorkerRecord worker, String charm) {
        if (worker == null) return Result.fail("Worker not found.");
        boolean unlocked = projects.projects(owner).stream().anyMatch(Project::completed);
        if (worker.getAssignedNodeId() != null) {
            unlocked |= nodeStore.getByOwner(owner).stream()
                    .anyMatch(node -> node.getId() == worker.getAssignedNodeId()
                            && node.getExplorationLevel() >= 50);
        }
        if (!unlocked) return Result.fail("Charm slot unlocks at Node Lv.50 or from a completed Project.");
        String normalized = charm.toUpperCase(Locale.ROOT);
        if (!CHARMS.contains(normalized)) {
            return Result.fail("Unknown Charm.");
        }
        state.put(owner, "WORKER", worker.getWorkerUuid().toString(), "charm", normalized);
        return Result.ok("Charm equipped: " + DesignText.pretty(normalized) + ".");
    }

    // ---- Training Notes -------------------------------------------------------

    public long trainingNotes(UUID owner) {
        return state.getLong(owner, "ACCOUNT", "-", "training_notes", 0);
    }

    public void addTrainingNotes(UUID owner, long amount) {
        if (amount > 0) state.increment(owner, "ACCOUNT", "-", "training_notes", amount);
    }

    public long takeTrainingNotes(UUID owner, long requested) {
        long available = trainingNotes(owner);
        long taken = Math.min(available, Math.max(0, requested));
        if (taken > 0) {
            state.put(owner, "ACCOUNT", "-", "training_notes", String.valueOf(available - taken));
        }
        return taken;
    }
}
