package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.worker.WorkerRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    // ---- bulk lane (Balance Bible §3): deterministic family commons ----

    /**
     * Bulk items/hour for a crew, before boosters and build multipliers.
     * Rarity mirrors vanilla tool dig speed and Diligence is Efficiency-like,
     * so the lane reads as "a worker with a real tool gathering commons".
     */
    public double bulkRatePerHour(NodeRecord node, List<WorkerRecord> crew) {
        double base = plugin.getConfig().getDouble(
                "production.bulk.base-per-hour."
                        + node.getType().name().toLowerCase(Locale.ROOT), 0.0);
        if (base <= 0 || crew.isEmpty()) {
            return 0;
        }
        double levelBonus = plugin.getConfig().getDouble("production.level-power-per-level", 0.02);
        double diligenceFactor = plugin.getConfig().getDouble("production.bulk.diligence-factor", 2.0);
        double power = 0;
        for (WorkerRecord worker : crew) {
            double rarityMult = plugin.getConfig().getDouble(
                    "production.bulk.rarity-mult." + worker.getRarity().name().toLowerCase(Locale.ROOT),
                    defaultBulkRarity(worker));
            power += rarityMult * (1 + worker.getLevel() * levelBonus)
                    * (1 + diligenceFactor * worker.getStats().diligence() / 100.0);
        }
        return base * power;
    }

    /**
     * Bulk buffer holds a fixed number of hours of the node's current rate;
     * higher tiers hold fewer hours so fill time stays constant as crews grow.
     */
    public int bulkBufferCapacity(NodeRecord node, double bulkRatePerHour) {
        int tierOneHours = Math.max(1, plugin.getConfig().getInt("production.bulk.buffer-hours.tier-1", 8));
        int minHours = Math.max(1, plugin.getConfig().getInt("production.bulk.buffer-hours.min", 4));
        int hours = Math.max(minHours, tierOneHours - (Math.max(1, node.getTier()) - 1));
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(hours * Math.max(0, bulkRatePerHour)));
    }

    /** Weighted family commons for the bulk lane; never empty. */
    public Map<String, Double> bulkCommons(NodeRecord node) {
        var section = plugin.getConfig().getConfigurationSection(
                "production.bulk.commons." + node.getType().name().toLowerCase(Locale.ROOT));
        Map<String, Double> commons = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                double weight = section.getDouble(key);
                if (weight > 0) {
                    commons.put(key.toUpperCase(Locale.ROOT), weight);
                }
            }
        }
        if (commons.isEmpty()) {
            commons.put(defaultBulkCommon(node), 1.0);
        }
        return commons;
    }

    private String defaultBulkCommon(NodeRecord node) {
        return switch (node.getType().name()) {
            case "FARMING" -> "WHEAT";
            case "WOODCUTTING" -> "OAK_LOG";
            case "LIVESTOCK" -> "BEEF";
            case "HUNTER" -> "ROTTEN_FLESH";
            default -> "COBBLESTONE";
        };
    }

    /**
     * Startup sanity check for the bulk lane config. Unlike the discovery
     * drop tables (which throw), bulk output has safe runtime fallbacks, so
     * these are warnings: a misconfigured lane still runs, but the operator is
     * told rather than silently getting cobblestone. Returns human-readable
     * problems; empty means clean.
     */
    public List<String> validateBulkConfig() {
        List<String> problems = new ArrayList<>();
        for (dev.branzx.idlefarm.node.NodeType type : dev.branzx.idlefarm.node.NodeType.values()) {
            if (!type.isProduction()) {
                continue;
            }
            String lower = type.name().toLowerCase(Locale.ROOT);
            double base = plugin.getConfig().getDouble("production.bulk.base-per-hour." + lower, -1);
            if (base < 0) {
                problems.add(type.name() + ": no production.bulk.base-per-hour entry "
                        + "(lane disabled for this type).");
                continue;
            }
            if (base == 0) {
                // Intentionally disabled; nothing else to check for this type.
                continue;
            }
            var section = plugin.getConfig().getConfigurationSection(
                    "production.bulk.commons." + lower);
            if (section == null || section.getKeys(false).isEmpty()) {
                problems.add(type.name() + ": bulk lane active (base=" + base
                        + ") but no production.bulk.commons list; using a single default common.");
                continue;
            }
            boolean anyPositive = false;
            for (String key : section.getKeys(false)) {
                double weight = section.getDouble(key);
                if (weight <= 0) {
                    problems.add(type.name() + ": common '" + key + "' has non-positive weight "
                            + weight + " (ignored).");
                    continue;
                }
                anyPositive = true;
                if (org.bukkit.Material.matchMaterial(key.toUpperCase(Locale.ROOT)) == null) {
                    problems.add(type.name() + ": common '" + key
                            + "' is not a valid item material (will fall back to a default).");
                }
            }
            if (!anyPositive) {
                problems.add(type.name() + ": no bulk common has a positive weight.");
            }
        }
        return problems;
    }

    /** Vanilla dig-speed ratios (wood/stone/iron/diamond/netherite), wood = 1. */
    private double defaultBulkRarity(WorkerRecord worker) {
        return switch (worker.getRarity()) {
            case COMMON -> 1.0;
            case UNCOMMON -> 2.0;
            case RARE -> 3.0;
            case EPIC -> 4.0;
            case LEGENDARY -> 4.5;
        };
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
