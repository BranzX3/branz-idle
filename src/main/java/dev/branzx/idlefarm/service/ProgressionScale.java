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
        double base = bulkBasePerHour(node.getType());
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
        return bulkCommons(node.getType());
    }

    public Map<String, Double> bulkCommons(dev.branzx.idlefarm.node.NodeType type) {
        var section = plugin.getConfig().getConfigurationSection(
                "production.bulk.commons." + type.name().toLowerCase(Locale.ROOT));
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
            return defaultBulkCommons(type);
        }
        return commons;
    }

    /**
     * Every material the bulk lane can produce, across all production families.
     * This is the classifier that routes a material to the Silo instead of the
     * Vault (docs/BALANCE_BIBLE.md §3): the two lanes already have separate
     * buffers on the node, and storage keeps that separation.
     */
    public java.util.Set<String> allBulkCommons() {
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (dev.branzx.idlefarm.node.NodeType type
                : dev.branzx.idlefarm.node.NodeType.values()) {
            if (type.isProduction()) {
                all.addAll(bulkCommons(type).keySet());
            }
        }
        return all;
    }

    /**
     * Bulk base rate for a family. The code default is the shipped design
     * value, not zero: a server whose config.yml predates the bulk lane never
     * receives the new section (Bukkit does not overwrite an existing config),
     * and defaulting to zero would silently disable the lane everywhere. An
     * explicit {@code 0} in config still disables it.
     */
    public double bulkBasePerHour(dev.branzx.idlefarm.node.NodeType type) {
        return plugin.getConfig().getDouble(
                "production.bulk.base-per-hour." + type.name().toLowerCase(Locale.ROOT),
                defaultBulkBase(type));
    }

    private double defaultBulkBase(dev.branzx.idlefarm.node.NodeType type) {
        return switch (type) {
            case MINING -> 70.0;
            case FARMING -> 45.0;
            case WOODCUTTING -> 35.0;
            case LIVESTOCK -> 18.0;
            case HUNTER -> 15.0;
            default -> 0.0;
        };
    }

    /**
     * Family commons used when config carries no list, mirroring the shipped
     * config.yml weights so an un-migrated server produces the intended mix
     * rather than a single material.
     */
    private Map<String, Double> defaultBulkCommons(dev.branzx.idlefarm.node.NodeType type) {
        Map<String, Double> commons = new LinkedHashMap<>();
        switch (type) {
            case MINING -> {
                commons.put("COBBLESTONE", 70.0);
                commons.put("COBBLED_DEEPSLATE", 20.0);
                commons.put("ANDESITE", 10.0);
            }
            case FARMING -> {
                commons.put("WHEAT", 40.0);
                commons.put("CARROT", 30.0);
                commons.put("POTATO", 30.0);
            }
            case WOODCUTTING -> {
                commons.put("OAK_LOG", 50.0);
                commons.put("BIRCH_LOG", 30.0);
                commons.put("SPRUCE_LOG", 20.0);
            }
            case LIVESTOCK -> {
                commons.put("BEEF", 40.0);
                commons.put("PORKCHOP", 30.0);
                commons.put("CHICKEN", 30.0);
            }
            case HUNTER -> {
                commons.put("ROTTEN_FLESH", 40.0);
                commons.put("BONE", 30.0);
                commons.put("STRING", 20.0);
                commons.put("ARROW", 10.0);
            }
            default -> commons.put("COBBLESTONE", 1.0);
        }
        return commons;
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
            boolean baseConfigured = plugin.getConfig().isSet("production.bulk.base-per-hour." + lower);
            double base = bulkBasePerHour(type);
            if (!baseConfigured) {
                problems.add(type.name() + ": no production.bulk.base-per-hour entry; running on the "
                        + "built-in default (" + base + "/hour). Add the production.bulk section to "
                        + "config.yml to tune it.");
            }
            if (base <= 0) {
                // Explicitly disabled; nothing else to check for this type.
                continue;
            }
            var section = plugin.getConfig().getConfigurationSection(
                    "production.bulk.commons." + lower);
            if (section == null || section.getKeys(false).isEmpty()) {
                problems.add(type.name() + ": no production.bulk.commons list; using the built-in "
                        + "default mix.");
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
