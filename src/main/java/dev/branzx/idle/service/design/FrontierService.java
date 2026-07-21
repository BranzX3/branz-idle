package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.service.WarehouseService;
import dev.branzx.idle.storage.GameStateStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Post-100 profession economy. Frontier equipment is node-bound state rather
 * than a vanilla item, so disabling future Frontier drops never invalidates or
 * removes materials/equipment already owned by a player.
 */
public final class FrontierService {

    public record Profession(String id, int level, long exp, long nextLevelExp) {
    }

    public record Equipment(String id, int tier, int durability, int maxDurability,
                            double productionBonus) {
        public boolean active() {
            return durability > 0;
        }
    }

    public record Recipe(String id, NodeType nodeType, int tier, int unlockLevel,
                         Map<String, Integer> materials, int maxDurability,
                         double productionBonus) {
    }

    private static final Map<NodeType, String> PROFESSIONS = Map.of(
            NodeType.MINING, "FORGEMASTER",
            NodeType.FARMING, "ALCHEMIST",
            NodeType.WOODCUTTING, "ARTIFICER",
            NodeType.LIVESTOCK, "LEATHERWORKER",
            NodeType.HUNTER, "RUNEWARDEN");

    private static final Map<NodeType, List<String>> MATERIALS = Map.of(
            NodeType.MINING, List.of("AETHER_ORE", "MYTHRIL_FRAGMENT", "RESONANT_CRYSTAL"),
            NodeType.FARMING, List.of("MANA_HERB", "LIFE_SEED", "ASTRAL_POLLEN"),
            NodeType.WOODCUTTING, List.of("SPIRITWOOD_RESIN", "ANCIENT_BARK", "LIVING_FIBER"),
            NodeType.LIVESTOCK, List.of("MYSTIC_HIDE", "BEAST_CORE", "PRIMAL_BLOOD"),
            NodeType.HUNTER, List.of("SOUL_ASH", "CORRUPTED_ESSENCE", "VOID_FANG"));

    private final IdlePlugin plugin;
    private final GameStateStore state;
    private final WarehouseService warehouse;
    private final FeatureControlService controls;
    private final TelemetryService telemetry;

    public FrontierService(IdlePlugin plugin, GameStateStore state,
                           WarehouseService warehouse, FeatureControlService controls,
                           TelemetryService telemetry) {
        this.plugin = plugin;
        this.state = state;
        this.warehouse = warehouse;
        this.controls = controls;
        this.telemetry = telemetry;
    }

    public boolean enabled(UUID owner) {
        return sinksReady() && controls.enabled("frontier", owner);
    }

    public boolean sinksReady() {
        return plugin.getConfig().getBoolean("frontier.enabled", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.profession", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.equipment", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.repair", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.project", false);
    }

    public Profession profession(UUID owner, NodeType type) {
        String id = PROFESSIONS.getOrDefault(type, "FRONTIER");
        long exp = state.getLong(owner, "FRONTIER_PROFESSION", id, "exp", 0);
        int level = professionLevel(exp);
        return new Profession(id, level, exp, expForLevel(level + 1));
    }

    public List<Recipe> recipes(NodeType type) {
        List<String> materials = MATERIALS.get(type);
        if (materials == null) return List.of();
        return List.of(recipe(type, 1, materials.get(0), 101, 12, 500, 0.02),
                recipe(type, 2, materials.get(1), 126, 24, 900, 0.04),
                recipe(type, 3, materials.get(2), 176, 40, 1500, 0.07));
    }

    private Recipe recipe(NodeType type, int tier, String material, int unlock,
                          int cost, int durability, double bonus) {
        String path = "frontier.equipment.tier-" + tier;
        int configuredCost = Math.max(1, plugin.getConfig().getInt(path + ".craft-cost", cost));
        int configuredDurability = Math.max(1,
                plugin.getConfig().getInt(path + ".durability", durability));
        double configuredBonus = Math.max(0,
                plugin.getConfig().getDouble(path + ".production-bonus", bonus));
        return new Recipe(type.name() + "_KIT_T" + tier, type, tier, unlock,
                Map.of(material, configuredCost), configuredDurability, configuredBonus);
    }

    public synchronized GameDesignService.Result craft(UUID owner, NodeRecord node, int tier) {
        if (!enabled(owner)) return GameDesignService.Result.fail("Frontier is not enabled for this account.");
        if (node == null || !owner.equals(node.getOwnerUuid()) || !node.getType().isProduction()) {
            return GameDesignService.Result.fail("Stand inside your production node.");
        }
        Recipe recipe = recipes(node.getType()).stream()
                .filter(candidate -> candidate.tier() == tier).findFirst().orElse(null);
        if (recipe == null) return GameDesignService.Result.fail("Unknown equipment tier.");
        if (node.getExplorationLevel() < recipe.unlockLevel()) {
            return GameDesignService.Result.fail("Requires node level " + recipe.unlockLevel() + ".");
        }
        Profession profession = profession(owner, node.getType());
        int professionRequired = (tier - 1) * 10 + 1;
        if (profession.level() < professionRequired) {
            return GameDesignService.Result.fail("Requires " + profession.id()
                    + " level " + professionRequired + ".");
        }
        if (!hasAll(owner, recipe.materials())) {
            return GameDesignService.Result.fail("Missing materials: " + format(recipe.materials()) + ".");
        }
        consume(owner, recipe.materials());
        state.put(owner, "FRONTIER_EQUIPMENT", String.valueOf(node.getId()), "id", recipe.id());
        state.put(owner, "FRONTIER_EQUIPMENT", String.valueOf(node.getId()), "tier",
                String.valueOf(recipe.tier()));
        state.put(owner, "FRONTIER_EQUIPMENT", String.valueOf(node.getId()), "durability",
                String.valueOf(recipe.maxDurability()));
        addProfessionExp(owner, node.getType(), recipe.tier() * 100L);
        telemetry.record(owner, "FRONTIER_CRAFT",
                "{\"recipe\":\"" + recipe.id() + "\",\"node\":" + node.getId() + "}");
        return GameDesignService.Result.ok("Crafted and equipped " + recipe.id()
                + " (" + recipe.maxDurability() + " durability).");
    }

    /**
     * Permanent profession sink. Any material belonging to the node's
     * profession may be refined into profession EXP.
     */
    public synchronized GameDesignService.Result train(UUID owner, NodeType type,
                                                       String material, int amount) {
        String normalized = material == null ? "" : material.toUpperCase(Locale.ROOT);
        if (!enabled(owner)) return GameDesignService.Result.fail("Frontier is not enabled for this account.");
        if (amount <= 0 || !MATERIALS.getOrDefault(type, List.of()).contains(normalized)) {
            return GameDesignService.Result.fail("Invalid profession material or amount.");
        }
        if (warehouse.getContents(owner).getOrDefault(normalized, 0) < amount) {
            return GameDesignService.Result.fail("Not enough " + normalized + ".");
        }
        warehouse.withdraw(owner, normalized, amount);
        long exp = amount * plugin.getConfig().getLong("frontier.profession-exp-per-material", 10);
        Profession next = addProfessionExp(owner, type, exp);
        telemetry.record(owner, "FRONTIER_TRAIN",
                "{\"material\":\"" + normalized + "\",\"amount\":" + amount + "}");
        return GameDesignService.Result.ok("Refined " + amount + " " + normalized
                + "; " + next.id() + " is level " + next.level() + ".");
    }

    public Equipment equipment(UUID owner, NodeRecord node) {
        if (node == null) return null;
        String scopeId = String.valueOf(node.getId());
        String id = state.get(owner, "FRONTIER_EQUIPMENT", scopeId, "id");
        if (id == null) return null;
        int tier = state.getInt(owner, "FRONTIER_EQUIPMENT", scopeId, "tier", 1);
        Recipe recipe = recipes(node.getType()).stream()
                .filter(candidate -> candidate.tier() == tier).findFirst().orElse(null);
        if (recipe == null) return null;
        int durability = Math.max(0, state.getInt(owner, "FRONTIER_EQUIPMENT", scopeId,
                "durability", 0));
        return new Equipment(id, tier, durability, recipe.maxDurability(), recipe.productionBonus());
    }

    public double productionMultiplier(NodeRecord node) {
        Equipment equipment = equipment(node.getOwnerUuid(), node);
        return equipment == null || !equipment.active() ? 1.0 : 1.0 + equipment.productionBonus();
    }

    public void consumeDurability(NodeRecord node, int produced) {
        if (produced <= 0) return;
        Equipment equipment = equipment(node.getOwnerUuid(), node);
        if (equipment == null || !equipment.active()) return;
        int perPoint = Math.max(1,
                plugin.getConfig().getInt("frontier.equipment.items-per-durability", 25));
        int damage = Math.max(1, (produced + perPoint - 1) / perPoint);
        int next = Math.max(0, equipment.durability() - damage);
        state.put(node.getOwnerUuid(), "FRONTIER_EQUIPMENT", String.valueOf(node.getId()),
                "durability", String.valueOf(next));
    }

    public synchronized GameDesignService.Result repair(UUID owner, NodeRecord node) {
        if (!enabled(owner)) return GameDesignService.Result.fail("Frontier is not enabled for this account.");
        Equipment equipment = equipment(owner, node);
        if (equipment == null) return GameDesignService.Result.fail("This node has no Frontier equipment.");
        int missing = equipment.maxDurability() - equipment.durability();
        if (missing <= 0) return GameDesignService.Result.fail("Equipment is already fully repaired.");
        String material = MATERIALS.get(node.getType()).get(equipment.tier() - 1);
        int restoredPerItem = Math.max(1,
                plugin.getConfig().getInt("frontier.equipment.repair-per-material", 25));
        int cost = Math.max(1, (missing + restoredPerItem - 1) / restoredPerItem);
        if (warehouse.getContents(owner).getOrDefault(material, 0) < cost) {
            return GameDesignService.Result.fail("Repair requires " + cost + " " + material + ".");
        }
        warehouse.withdraw(owner, material, cost);
        state.put(owner, "FRONTIER_EQUIPMENT", String.valueOf(node.getId()), "durability",
                String.valueOf(equipment.maxDurability()));
        addProfessionExp(owner, node.getType(), cost * 2L);
        telemetry.record(owner, "FRONTIER_REPAIR",
                "{\"node\":" + node.getId() + ",\"material\":\"" + material
                        + "\",\"amount\":" + cost + "}");
        return GameDesignService.Result.ok("Repaired " + equipment.id() + " for "
                + cost + " " + material + ".");
    }

    private Profession addProfessionExp(UUID owner, NodeType type, long amount) {
        String id = PROFESSIONS.getOrDefault(type, "FRONTIER");
        state.increment(owner, "FRONTIER_PROFESSION", id, "exp", Math.max(0, amount));
        return profession(owner, type);
    }

    private int professionLevel(long exp) {
        int level = 1;
        while (level < 50 && exp >= expForLevel(level + 1)) level++;
        return level;
    }

    private long expForLevel(int level) {
        int previous = Math.max(0, level - 1);
        return 100L * previous * previous;
    }

    private boolean hasAll(UUID owner, Map<String, Integer> costs) {
        Map<String, Integer> contents = warehouse.getContents(owner);
        return costs.entrySet().stream()
                .allMatch(entry -> contents.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    private void consume(UUID owner, Map<String, Integer> costs) {
        costs.forEach((material, amount) -> warehouse.withdraw(owner, material, amount));
    }

    private String format(Map<String, Integer> costs) {
        Map<String, Integer> ordered = new LinkedHashMap<>(costs);
        return ordered.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
