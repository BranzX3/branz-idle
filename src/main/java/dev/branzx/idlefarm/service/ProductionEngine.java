package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The idle core. Production is pure math over timestamps: every tick each
 * production node credits {@code floor(rate × elapsed)} items into its
 * buffer, advancing {@code last_tick_at} only by the time actually consumed
 * (fractional progress is never lost). The first tick after a restart
 * therefore also settles all downtime — true 24/7 accrual with no per-player
 * schedulers.
 *
 * Rate per spec: base_rate(type) × Σ [ base_power(rarity, level) × (1 + diligence/100) ].
 * Buffer full ⇒ node STORAGE_FULL and workers STOP (NPC visuals follow).
 */
public final class ProductionEngine extends BukkitRunnable {

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final WorkerService workerService;
    private final ProgressionScale scale;
    private ExplorationService explorationService;
    private BoosterService boosterService;
    private PerkService perkService;
    private WarehouseService warehouseService;
    private GlobalExpeditionService globalExpeditionService;
    private GameDesignService gameDesignService;

    public void setExplorationService(ExplorationService explorationService) {
        this.explorationService = explorationService;
    }

    public void setBoosterService(BoosterService boosterService) {
        this.boosterService = boosterService;
    }

    public void setPerkServices(PerkService perkService, WarehouseService warehouseService) {
        this.perkService = perkService;
        this.warehouseService = warehouseService;
    }

    public void setGlobalExpeditionService(GlobalExpeditionService globalExpeditionService) {
        this.globalExpeditionService = globalExpeditionService;
    }

    public void setGameDesignService(GameDesignService gameDesignService) {
        this.gameDesignService = gameDesignService;
    }

    private DropTableService dropTableService;

    public void setDropTableService(DropTableService dropTableService) {
        this.dropTableService = dropTableService;
    }

    public ProductionEngine(IdleFarmPlugin plugin, NodeStore nodeStore,
                            WorkerStore workerStore, WorkerService workerService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.workerService = workerService;
        this.scale = new ProgressionScale(plugin);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (NodeRecord node : nodeStore.getAll()) {
            if (node.getType().isProduction()) {
                tickNode(node, now);
            }
        }
    }

    private void tickNode(NodeRecord node, long now) {
        List<WorkerRecord> crew = workerStore.getAssigned(node.getId()).stream()
                .filter(w -> !WorkerRecord.STATE_EXPLORING.equals(w.getState()))
                .filter(w -> globalExpeditionService == null
                        || !globalExpeditionService.isCommitted(w.getWorkerUuid()))
                .toList();

        // No workers = no production (spec: workers are the engine). The
        // anchor still advances so hiring later doesn't backfill idle time.
        if (crew.isEmpty()) {
            if (explorationService != null) {
                explorationService.advancePassiveResearch(node, crew, now,
                        node.storageTotal() >= bufferCapacity(node));
            }
            node.setState(NodeRecord.STATE_IDLE);
            node.setLastTickAt(now);
            // Persist the idle anchor as well as the state. Otherwise a
            // restart can restore an old timestamp and backfill unstaffed
            // time after a worker is eventually assigned.
            nodeStore.updateProduction(node);
            return;
        }

        double boost = boosterService == null ? 1.0
                : boosterService.multiplier(node.getOwnerUuid(), BoosterService.PRODUCTION);
        double buildMultiplier = gameDesignService == null ? 1.0
                : gameDesignService.productionMultiplier(node);
        double ratePerHour = baseRate(node) * crewPower(crew) * boost * buildMultiplier;
        if (ratePerHour <= 0) {
            if (explorationService != null) {
                explorationService.advancePassiveResearch(node, crew, now,
                        node.storageTotal() >= bufferCapacity(node));
            }
            node.setLastTickAt(now);
            return;
        }

        int capacity = bufferCapacity(node);
        int space = capacity - node.storageTotal();
        boolean wasFull = space <= 0;
        double elapsedHours = (now - node.getLastTickAt()) / 3_600_000.0;
        int produced = (int) Math.floor(ratePerHour * elapsedHours);
        int credited = Math.min(produced, Math.max(0, space));

        boolean dirty = false;
        if (credited > 0) {
            java.util.Map<String, Integer> producedItems = rollItems(node, credited);
            if (gameDesignService != null) {
                gameDesignService.onItemsProduced(node, producedItems);
            }
            // Advance only by the time actually converted into items.
            node.setLastTickAt(node.getLastTickAt() + (long) (credited / ratePerHour * 3_600_000.0));
            grantCrewExp(crew, credited);
            dirty = true;
        }

        // Research scales with staffed time, not item count. This keeps slow
        // Hunter nodes aligned with fast Farming nodes and ignores boosters.
        if (explorationService != null
                && explorationService.advancePassiveResearch(node, crew, now, wasFull) > 0) {
            dirty = true;
        }

        // Auto-collect perk: buffer flushes straight to the Warehouse.
        if (perkService != null && warehouseService != null && node.storageTotal() > 0
                && perkService.has(node.getOwnerUuid(), PerkService.AUTO_COLLECT)) {
            int autoCollected = warehouseService.collectNode(node);
            if (autoCollected > 0 && gameDesignService != null) {
                gameDesignService.onBufferCollected(node, autoCollected);
            }
            dirty = true;
        }

        boolean full = node.storageTotal() >= capacity;
        if (full) {
            // Buffer full: production halts and the anchor rides forward so
            // no phantom backlog accrues while stopped.
            node.setLastTickAt(now);
        }
        dirty |= applyStates(node, crew, full);
        if (dirty) {
            nodeStore.updateProduction(node);
        }
    }

    private double baseRate(NodeRecord node) {
        return plugin.getConfig().getDouble(
                "production.base-items-per-hour." + node.getType().name().toLowerCase(Locale.ROOT), 30.0);
    }

    private double crewPower(List<WorkerRecord> crew) {
        double levelBonus = plugin.getConfig().getDouble("production.level-power-per-level", 0.02);
        double power = 0;
        for (WorkerRecord worker : crew) {
            double rarityPower = plugin.getConfig().getDouble(
                    "production.rarity-power." + worker.getRarity().name().toLowerCase(Locale.ROOT), 1.0);
            double basePower = rarityPower * (1 + worker.getLevel() * levelBonus);
            if (gameDesignService != null
                    && "HEAVY_GLOVES".equals(gameDesignService.workerCharm(worker.getWorkerUuid()))) {
                basePower *= 1.10;
            }
            power += basePower * (1 + worker.getStats().diligence() / 100.0);
        }
        return power;
    }

    public int bufferCapacity(NodeRecord node) {
        double multiplier = gameDesignService == null ? 1.0 : gameDesignService.bufferMultiplier(node);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(scale.bufferCapacity(node) * multiplier));
    }

    private java.util.Map<String, Integer> rollItems(NodeRecord node, int count) {
        int bracket = explorationService == null ? 1 : explorationService.bracket(node);
        java.util.Map<String, Double> baseTable = dropTableService != null
                ? dropTableService.table(node.getType(), bracket, node.getExplorationLevel())
                : java.util.Map.of("cobblestone", 1.0);
        if (baseTable.isEmpty()) {
            // Startup validation normally makes this unreachable. Retaining a
            // deterministic common fallback prevents a live node tick from
            // crashing if an external file is corrupted after startup.
            plugin.getLogger().severe("Empty runtime drop table for " + node.getType()
                    + " bracket " + bracket + "; using COBBLESTONE fallback.");
            baseTable = java.util.Map.of("cobblestone", 1.0);
        }
        java.util.Map<String, Double> table = new java.util.LinkedHashMap<>();
        baseTable.forEach((material, weight) -> table.put(material,
                weight * (gameDesignService == null ? 1.0
                        : gameDesignService.resourceWeightMultiplier(node, material))));
        double totalWeight = table.values().stream().mapToDouble(Double::doubleValue).sum();
        java.util.Map<String, Integer> produced = new java.util.LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String selected = null;
            for (int attempt = 0; attempt < 8 && selected == null; attempt++) {
                double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
                double cumulative = 0;
                for (var entry : table.entrySet()) {
                    cumulative += entry.getValue();
                    if (roll < cumulative) {
                        String candidate = entry.getKey().toUpperCase(Locale.ROOT);
                        if (gameDesignService == null || gameDesignService.allowResource(
                                node.getOwnerUuid(), candidate, node.getExplorationLevel())) {
                            selected = candidate;
                        }
                        break;
                    }
                }
            }
            if (selected == null) {
                // A capped result is rerolled into a safe common output, never
                // silently deleted.
                selected = table.keySet().stream()
                        .map(key -> key.toUpperCase(Locale.ROOT))
                        .filter(key -> !java.util.Set.of("DIAMOND", "EMERALD", "NAUTILUS_SHELL",
                                "GHAST_TEAR", "ANCIENT_DEBRIS", "NETHERITE_SCRAP",
                                "WITHER_SKELETON_SKULL").contains(key))
                        .findFirst().orElse("COBBLESTONE");
            }
            node.getStorage().merge(selected, 1, Integer::sum);
            produced.merge(selected, 1, Integer::sum);
        }
        return produced;
    }

    private void grantCrewExp(List<WorkerRecord> crew, int credited) {
        double expPerItem = plugin.getConfig().getDouble("production.worker-exp-per-item", 2.0);
        for (WorkerRecord worker : crew) {
            long exp = (long) Math.ceil(expPerItem * credited / crew.size()
                    * (1 + worker.getStats().stamina() / 100.0));
            workerService.grantExp(worker, exp);
        }
    }

    /** Sync node + worker states with buffer fullness; true if node state changed. */
    private boolean applyStates(NodeRecord node, List<WorkerRecord> crew, boolean full) {
        String target = full ? NodeRecord.STATE_STORAGE_FULL : NodeRecord.STATE_ACTIVE;
        String workerTarget = full ? WorkerRecord.STATE_STOP : WorkerRecord.STATE_WORKING;
        boolean changed = !target.equals(node.getState());
        node.setState(target);
        for (WorkerRecord worker : crew) {
            if (!workerTarget.equals(worker.getState())) {
                worker.setState(workerTarget);
                workerStore.update(worker);
            }
        }
        return changed;
    }
}
