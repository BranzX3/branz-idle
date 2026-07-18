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

    public ProductionEngine(IdleFarmPlugin plugin, NodeStore nodeStore,
                            WorkerStore workerStore, WorkerService workerService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.workerService = workerService;
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
                .toList();

        // No workers = no production (spec: workers are the engine). The
        // anchor still advances so hiring later doesn't backfill idle time.
        if (crew.isEmpty()) {
            node.setLastTickAt(now);
            return;
        }

        double ratePerHour = baseRate(node) * crewPower(crew);
        if (ratePerHour <= 0) {
            node.setLastTickAt(now);
            return;
        }

        int capacity = bufferCapacity(node);
        int space = capacity - node.storageTotal();
        double elapsedHours = (now - node.getLastTickAt()) / 3_600_000.0;
        int produced = (int) Math.floor(ratePerHour * elapsedHours);
        int credited = Math.min(produced, Math.max(0, space));

        boolean dirty = false;
        if (credited > 0) {
            rollItems(node, credited);
            // Advance only by the time actually converted into items.
            node.setLastTickAt(node.getLastTickAt() + (long) (credited / ratePerHour * 3_600_000.0));
            grantCrewExp(crew, credited);
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
            power += basePower * (1 + worker.getStats().diligence() / 100.0);
        }
        return power;
    }

    public int bufferCapacity(NodeRecord node) {
        int base = plugin.getConfig().getInt("production.buffer-capacity-per-tier", 64);
        return base * node.getTier();
    }

    private void rollItems(NodeRecord node, int count) {
        ConfigurationSection table = plugin.getConfig().getConfigurationSection(
                "production.drop-tables." + node.getType().name().toLowerCase(Locale.ROOT));
        if (table == null) {
            node.getStorage().merge("COBBLESTONE", count, Integer::sum);
            return;
        }
        List<String> materials = List.copyOf(table.getKeys(false));
        double totalWeight = materials.stream().mapToDouble(table::getDouble).sum();
        for (int i = 0; i < count; i++) {
            double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
            double cumulative = 0;
            for (String material : materials) {
                cumulative += table.getDouble(material);
                if (roll < cumulative) {
                    node.getStorage().merge(material.toUpperCase(Locale.ROOT), 1, Integer::sum);
                    break;
                }
            }
        }
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
        String target = full ? "STORAGE_FULL" : "ACTIVE";
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
