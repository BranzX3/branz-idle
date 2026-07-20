package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.worker.WorkerRecord;

import java.util.List;
import java.util.Locale;

/**
 * Single authority for the numeric progression rules shared by production,
 * exploration and UI. Keeping these rules together prevents Tier (quantity)
 * and Exploration Level (discovery/quality) from accidentally scaling the
 * same axis.
 */
public final class ProgressionScale {

    private final IdleFarmPlugin plugin;

    public ProgressionScale(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public int levelCap() {
        boolean sinksReady = plugin.getConfig().getBoolean("frontier.sink-gates.profession", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.equipment", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.repair", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.project", false);
        if (plugin.getConfig().getBoolean("frontier.enabled", false) && sinksReady) {
            return plugin.getConfig().getInt("frontier.level-cap", 200);
        }
        return plugin.getConfig().getInt("exploration.level-cap", 100);
    }

    /** Ceiling for audited manual content staging; not automatic progression. */
    public int adminLevelCap() {
        return Math.max(levelCap(),
                plugin.getConfig().getInt("exploration.admin-level-cap", 1000));
    }

    public long expForNextLevel(int currentLevel) {
        if (currentLevel >= levelCap()) {
            return 0;
        }
        long base = plugin.getConfig().getLong("exploration.exp-curve.base", 120);
        long perLevel = plugin.getConfig().getLong("exploration.exp-curve.per-level", 12);
        return Math.max(1, base + perLevel * Math.max(1, currentLevel));
    }

    public int bracket(int level) {
        int size = Math.max(1, plugin.getConfig().getInt("exploration.bracket-size", 10));
        int vanillaBrackets = Math.max(1,
                plugin.getConfig().getInt("exploration.vanilla-brackets", 10));
        int maxBrackets = Math.max(vanillaBrackets,
                plugin.getConfig().getInt("exploration.max-brackets", 100));
        int raw = Math.max(1, level) / size + 1;
        if (level <= vanillaBrackets * size) {
            return Math.min(vanillaBrackets, raw);
        }
        return Math.min(maxBrackets, raw);
    }

    public int bufferCapacity(NodeRecord node) {
        int perTier = Math.max(1,
                plugin.getConfig().getInt("production.buffer-capacity-per-tier", 256));
        long capacity = (long) perTier * Math.max(1, node.getTier());
        return (int) Math.min(Integer.MAX_VALUE, capacity);
    }

    public int passiveResearchDailyCap() {
        return Math.max(0, plugin.getConfig().getInt(
                "exploration.passive-research.daily-cap", 900));
    }

    public long restedResearchMillis() {
        long hours = Math.max(0, plugin.getConfig().getLong(
                "exploration.passive-research.rested-hours", 48));
        return hours > Long.MAX_VALUE / 3_600_000L
                ? Long.MAX_VALUE
                : hours * 3_600_000L;
    }

    public double passiveResearchPerHour(List<WorkerRecord> crew, boolean bufferFull) {
        if (crew.isEmpty()) {
            return 0;
        }
        double base = plugin.getConfig().getDouble(
                "exploration.passive-research.base-per-hour", 75.0);
        double extraWorker = plugin.getConfig().getDouble(
                "exploration.passive-research.extra-worker-bonus", 0.15);
        double levelPower = plugin.getConfig().getDouble(
                "exploration.passive-research.level-power-per-level", 0.005);

        double averagePower = crew.stream().mapToDouble(worker -> {
            double rarity = plugin.getConfig().getDouble(
                    "exploration.passive-research.rarity-factor."
                            + worker.getRarity().name().toLowerCase(Locale.ROOT),
                    defaultResearchRarity(worker));
            return rarity * (1 + worker.getStats().stamina() / 100.0
                    + worker.getLevel() * levelPower);
        }).average().orElse(0);

        double countBonus = 1 + extraWorker * Math.max(0, crew.size() - 1);
        double stateMultiplier = bufferFull
                ? plugin.getConfig().getDouble(
                        "exploration.passive-research.full-buffer-multiplier", 0.25)
                : 1.0;
        return Math.max(0, base * countBonus * averagePower * stateMultiplier);
    }

    private double defaultResearchRarity(WorkerRecord worker) {
        return switch (worker.getRarity()) {
            case COMMON -> 1.00;
            case UNCOMMON -> 1.05;
            case RARE -> 1.10;
            case EPIC -> 1.15;
            case LEGENDARY -> 1.20;
        };
    }
}
