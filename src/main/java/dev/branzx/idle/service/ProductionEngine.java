package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.command.CommandLinks;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The idle core. Production is pure math over timestamps: every tick each
 * production node credits {@code floor(rate × elapsed)} items into its
 * buffer, advancing the lane anchor only by the time actually consumed
 * (fractional progress is never lost). The first tick after a restart
 * therefore also settles all downtime — true 24/7 accrual with no per-player
 * schedulers.
 *
 * Two lanes per Balance Bible §3, each with its own anchor and buffer:
 * - Bulk lane: deterministic family commons at
 *   {@code bulk_base(type) × Σ [ rarity_mult × (1 + level×0.02) × (1 + 2×diligence/100) ]}.
 * - Discovery lane: weighted drop-table rolls at
 *   {@code base_rate(type) × Σ [ base_power(rarity, level) × (1 + diligence/100) ]}.
 *
 * A full buffer stops only its own lane. Node STORAGE_FULL and worker STOP
 * (NPC visuals follow) only when every enabled lane is stopped.
 */
public final class ProductionEngine extends BukkitRunnable {

    private final IdlePlugin plugin;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final WorkerService workerService;
    private final ProgressionScale scale;
    private final ExplorationService explorationService;
    private final BoosterService boosterService;
    private final PerkService perkService;
    private final WarehouseService warehouseService;
    private final GlobalExpeditionService globalExpeditionService;
    private final GameDesignService gameDesignService;
    private final DropTableService dropTableService;

    public ProductionEngine(IdlePlugin plugin, NodeStore nodeStore,
                            WorkerStore workerStore, WorkerService workerService,
                            ExplorationService explorationService, BoosterService boosterService,
                            PerkService perkService, WarehouseService warehouseService,
                            DropTableService dropTableService,
                            GlobalExpeditionService globalExpeditionService,
                            GameDesignService gameDesignService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.workerService = workerService;
        this.scale = new ProgressionScale(plugin);
        this.explorationService = explorationService;
        this.boosterService = boosterService;
        this.perkService = perkService;
        this.warehouseService = warehouseService;
        this.dropTableService = dropTableService;
        this.globalExpeditionService = globalExpeditionService;
        this.gameDesignService = gameDesignService;
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

    /** The workers that actually drive production: assigned, not exploring,
     *  not committed to a Global Expedition. Shared by the tick loop and the
     *  live-rate queries the UI uses, so both see the same crew. */
    private List<WorkerRecord> activeCrew(NodeRecord node) {
        return workerStore.getAssigned(node.getId()).stream()
                .filter(w -> !WorkerRecord.STATE_EXPLORING.equals(w.getState()))
                .filter(w -> globalExpeditionService == null
                        || !globalExpeditionService.isCommitted(w.getWorkerUuid()))
                .toList();
    }

    private void tickNode(NodeRecord node, long now) {
        List<WorkerRecord> crew = activeCrew(node);

        // No workers = no production (spec: workers are the engine). The
        // anchors still advance so hiring later doesn't backfill idle time.
        if (crew.isEmpty()) {
            if (explorationService != null) {
                explorationService.advancePassiveResearch(node, crew, now,
                        node.storageTotal() >= bufferCapacity(node));
            }
            node.setState(NodeRecord.STATE_IDLE);
            node.setLastTickAt(now);
            node.setBulkLastTickAt(now);
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

        // Research penalty and the stop state consider every enabled lane, so
        // capture fullness before either lane credits new items.
        double bulkRatePerHour = bulkRatePerHour(node, crew, boost, buildMultiplier);
        boolean bulkEnabled = bulkRatePerHour > 0;
        int bulkCapacity = bulkEnabled ? bulkBufferCapacity(node, bulkRatePerHour) : 0;
        int capacity = bufferCapacity(node);
        double ratePerHour = baseRate(node) * crewPower(crew) * boost * buildMultiplier;
        boolean wasFull = node.storageTotal() >= capacity
                && (!bulkEnabled || node.bulkStorageTotal() >= bulkCapacity);

        boolean dirty = tickBulkLane(node, now, bulkRatePerHour, bulkCapacity);

        if (ratePerHour <= 0) {
            node.setLastTickAt(now);
        } else {
            int space = capacity - node.storageTotal();
            double elapsedHours = (now - node.getLastTickAt()) / 3_600_000.0;
            int produced = (int) Math.floor(ratePerHour * elapsedHours);
            int credited = Math.min(produced, Math.max(0, space));
            if (credited > 0) {
                java.util.Map<String, Integer> producedItems = rollItems(node, credited);
                if (gameDesignService != null) {
                    gameDesignService.onItemsProduced(node, producedItems);
                    gameDesignService.consumeFrontierDurability(node, credited);
                }
                // Advance only by the time actually converted into items.
                node.setLastTickAt(node.getLastTickAt() + (long) (credited / ratePerHour * 3_600_000.0));
                grantCrewExp(crew, credited);
                dirty = true;
            }
        }

        // Research scales with staffed time, not item count. This keeps slow
        // Hunter nodes aligned with fast Farming nodes and ignores boosters.
        if (explorationService != null
                && explorationService.advancePassiveResearch(node, crew, now, wasFull) > 0) {
            dirty = true;
        }

        // Auto-collect perk: buffers flush straight to the Warehouse.
        if (perkService != null && warehouseService != null
                && node.storageTotal() + node.bulkStorageTotal() > 0
                && perkService.has(node.getOwnerUuid(), PerkService.AUTO_COLLECT)) {
            int autoCollected = warehouseService.collectNode(node);
            if (autoCollected > 0 && gameDesignService != null) {
                gameDesignService.onBufferCollected(node, autoCollected);
            }
            dirty = true;
        }

        boolean discoveryFull = node.storageTotal() >= capacity;
        boolean full = discoveryFull
                && (!bulkEnabled || node.bulkStorageTotal() >= bulkCapacity);
        if (discoveryFull) {
            // Buffer full: the lane halts and its anchor rides forward so no
            // phantom backlog accrues while stopped.
            node.setLastTickAt(now);
        }
        boolean stateChanged = applyStates(node, crew, full);
        if (stateChanged && full) {
            notifyBufferFull(node);
        }
        dirty |= stateChanged;
        if (dirty) {
            nodeStore.updateProduction(node);
        }
    }

    /**
     * Deterministic commons accrual (Balance Bible §3). No rolls, no worker
     * EXP and no frontier durability: those stay coupled to discovery items
     * so introducing the bulk lane leaves every discovery-side budget
     * unchanged. Returns true when the node record changed.
     */
    private boolean tickBulkLane(NodeRecord node, long now, double ratePerHour, int capacity) {
        if (ratePerHour <= 0) {
            node.setBulkLastTickAt(now);
            return false;
        }
        boolean dirty = false;
        int space = capacity - node.bulkStorageTotal();
        double elapsedHours = (now - node.getBulkLastTickAt()) / 3_600_000.0;
        int produced = (int) Math.floor(ratePerHour * elapsedHours);
        int credited = Math.min(produced, Math.max(0, space));
        if (credited > 0) {
            distributeBulk(node, credited);
            node.setBulkLastTickAt(node.getBulkLastTickAt()
                    + (long) (credited / ratePerHour * 3_600_000.0));
            dirty = true;
        }
        if (node.bulkStorageTotal() >= capacity) {
            node.setBulkLastTickAt(now);
        }
        return dirty;
    }

    /** Splits credited bulk output across the family commons by weight. */
    private void distributeBulk(NodeRecord node, int credited) {
        java.util.Map<String, Double> commons = scale.bulkCommons(node);
        double totalWeight = commons.values().stream().mapToDouble(Double::doubleValue).sum();
        String heaviest = commons.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElseThrow();
        int remaining = credited;
        for (var entry : commons.entrySet()) {
            int amount = (int) Math.floor(credited * entry.getValue() / totalWeight);
            if (amount > 0) {
                node.getBulkStorage().merge(entry.getKey(), amount, Integer::sum);
                remaining -= amount;
            }
        }
        if (remaining > 0) {
            node.getBulkStorage().merge(heaviest, remaining, Integer::sum);
        }
    }

    /** Bulk buffer with Logistics/build multipliers applied, like {@link #bufferCapacity}. */
    public int bulkBufferCapacity(NodeRecord node, double bulkRatePerHour) {
        double multiplier = gameDesignService == null ? 1.0 : gameDesignService.bufferMultiplier(node);
        return (int) Math.min(Integer.MAX_VALUE,
                Math.round(scale.bulkBufferCapacity(node, bulkRatePerHour) * multiplier));
    }

    /** Bulk items/hour for the node's live crew, with boosters and build
     *  multipliers — the exact rate the tick loop credits at. */
    private double bulkRatePerHour(NodeRecord node, List<WorkerRecord> crew,
                                   double boost, double buildMultiplier) {
        return scale.bulkRatePerHour(node, crew) * boost * buildMultiplier;
    }

    /**
     * Live bulk-buffer capacity for the node's current crew, so the UI shows
     * the same denominator the engine fills against. Returns 0 when the lane
     * is inactive (no crew, or the type's bulk rate is disabled), letting the
     * caller fall back to a plain count instead of a 0/0 bar.
     */
    public int currentBulkCapacity(NodeRecord node) {
        List<WorkerRecord> crew = activeCrew(node);
        if (crew.isEmpty()) {
            return 0;
        }
        double boost = boosterService == null ? 1.0
                : boosterService.multiplier(node.getOwnerUuid(), BoosterService.PRODUCTION);
        double buildMultiplier = gameDesignService == null ? 1.0
                : gameDesignService.productionMultiplier(node);
        double rate = bulkRatePerHour(node, crew, boost, buildMultiplier);
        return rate <= 0 ? 0 : bulkBufferCapacity(node, rate);
    }

    /**
     * One chat line on the transition to STORAGE_FULL only; the join summary
     * covers transitions that happen while the owner is offline.
     */
    private void notifyBufferFull(NodeRecord node) {
        Player owner = Bukkit.getPlayer(node.getOwnerUuid());
        if (owner == null) {
            return;
        }
        owner.sendMessage(Component.text()
                .append(Component.text("[Production] ", NamedTextColor.GOLD))
                .append(Component.text(node.getType()
                        + " node buffer is full — production stopped. ",
                        NamedTextColor.YELLOW))
                .append(CommandLinks.run("[Collect]", "/idle collect " + node.getId()))
                .append(Component.space())
                .append(CommandLinks.run("[Open Nodes]", "/idle nodes"))
                .build());
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
                ? dropTableService.table(node.getType(), bracket, node.getExplorationLevel(),
                node.getOwnerUuid())
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
