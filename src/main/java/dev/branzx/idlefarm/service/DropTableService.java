package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Material;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Drop tables live in their own {@code drops.yml} (seeded from config.yml
 * defaults on first run) so the in-game Pool Editor can write them back
 * without clobbering the commented main config. Supports optional
 * bracket-N subsections per node type, same as before.
 */
public final class DropTableService {

    private final IdleFarmPlugin plugin;
    private final File file;
    private YamlConfiguration yaml;

    public DropTableService(IdleFarmPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drops.yml");
    }

    public void load() {
        if (!file.exists()) {
            seedFromConfig();
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
        mergeMissingDefaults();
        sanitizeForbiddenPassiveDrops();
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            errors.forEach(error -> plugin.getLogger().severe("Drop-table validation: " + error));
            throw new IllegalStateException("Refusing to publish invalid drop tables ("
                    + errors.size() + " validation errors)");
        }
    }

    private void sanitizeForbiddenPassiveDrops() {
        Set<String> forbidden = Set.of("ELYTRA", "NETHER_STAR", "DRAGON_EGG", "HEAVY_CORE",
                "ENCHANTED_GOLDEN_APPLE", "WITHER_SKELETON_SKULL");
        boolean changed = false;
        for (String path : new java.util.ArrayList<>(yaml.getKeys(true))) {
            if (yaml.isConfigurationSection(path)) continue;
            String leaf = path.substring(path.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
            if ("WOOL".equals(leaf)) {
                String parent = path.substring(0, path.lastIndexOf('.') + 1);
                if (!yaml.isSet(parent + "white_wool")) {
                    yaml.set(parent + "white_wool", yaml.get(path));
                }
                yaml.set(path, null);
                changed = true;
                continue;
            }
            if (forbidden.contains(leaf)) {
                yaml.set(path, null);
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Adds newly shipped brackets without overwriting administrator tuning. */
    private void mergeMissingDefaults() {
        ConfigurationSection defaults = plugin.getConfig().getConfigurationSection("production.drop-tables");
        if (defaults == null) return;
        boolean changed = false;
        for (Map.Entry<String, Object> entry : defaults.getValues(true).entrySet()) {
            if (!(entry.getValue() instanceof ConfigurationSection)
                    && !yaml.isSet(entry.getKey())) {
                yaml.set(entry.getKey(), entry.getValue());
                changed = true;
            }
        }
        if (changed) save();
    }

    /**
     * Content publish gate: every launch family must have ten non-empty
     * effective brackets and every output must be a real item material.
     */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        for (NodeType type : NodeType.values()) {
            if (!type.isProduction()) continue;
            for (int bracket = 1; bracket <= 10; bracket++) {
                Map<String, Double> effective = table(type, bracket);
                if (effective.isEmpty()) {
                    errors.add(type + " bracket-" + bracket + " is empty");
                    continue;
                }
                for (Map.Entry<String, Double> entry : effective.entrySet()) {
                    Material material = Material.matchMaterial(entry.getKey());
                    if (material == null || !material.isItem()) {
                        errors.add(type + " bracket-" + bracket + " has invalid item " + entry.getKey());
                    }
                    if (!Double.isFinite(entry.getValue()) || entry.getValue() <= 0) {
                        errors.add(type + " bracket-" + bracket + " has invalid weight for " + entry.getKey());
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    public Map<String, Double> additions(NodeType type, int bracket) {
        if (bracket <= 1) return table(type, 1);
        Map<String, Double> previous = table(type, bracket - 1);
        Map<String, Double> current = table(type, bracket);
        Map<String, Double> additions = new LinkedHashMap<>();
        current.forEach((material, weight) -> {
            if (!previous.containsKey(material)) additions.put(material, weight);
        });
        return additions;
    }

    private void seedFromConfig() {
        YamlConfiguration seed = new YamlConfiguration();
        ConfigurationSection defaults = plugin.getConfig().getConfigurationSection("production.drop-tables");
        if (defaults != null) {
            // Deep copy (handles flat tables and nested bracket-N sections).
            for (Map.Entry<String, Object> entry : defaults.getValues(true).entrySet()) {
                if (!(entry.getValue() instanceof ConfigurationSection)) {
                    seed.set(entry.getKey(), entry.getValue());
                }
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            seed.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to seed drops.yml: " + e.getMessage());
        }
    }

    /** Bracket sub-tables a type defines (empty if it uses a flat table). */
    public List<String> brackets(NodeType type) {
        ConfigurationSection section = yaml.getConfigurationSection(type.name().toLowerCase(Locale.ROOT));
        List<String> result = new java.util.ArrayList<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key.startsWith("bracket-")) {
                    result.add(key);
                }
            }
        }
        result.sort(java.util.Comparator.comparingInt(this::bracketNumber));
        return result;
    }

    private int bracketNumber(String key) {
        try {
            return Integer.parseInt(key.substring("bracket-".length()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Effective weighted table for a node type at a bracket. Bracket sections
     * are additive: later brackets override weights they explicitly tune and
     * retain every earlier resource. A flat section applies to every bracket.
     */
    public Map<String, Double> table(NodeType type, int bracket) {
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        ConfigurationSection section = yaml.getConfigurationSection(typeKey);
        if (section == null) {
            return Map.of();
        }
        if (section.getConfigurationSection("bracket-1") != null) {
            Map<String, Double> cumulative = new LinkedHashMap<>();
            for (int i = 1; i <= bracket; i++) {
                ConfigurationSection candidate = section.getConfigurationSection("bracket-" + i);
                if (candidate != null) {
                    for (String key : candidate.getKeys(false)) {
                        if (!candidate.isConfigurationSection(key)) {
                            double weight = candidate.getDouble(key);
                            if (weight > 0) {
                                cumulative.put(key, weight);
                            } else {
                                cumulative.remove(key);
                            }
                        }
                    }
                }
            }
            return Map.copyOf(cumulative);
        }
        Map<String, Double> table = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            table.put(key, section.getDouble(key));
        }
        return Map.copyOf(table);
    }

    /**
     * Effective table including the staged post-100 material share. Vanilla
     * output is always retained and disabling the flag stops all future
     * Frontier drops without touching owned items.
     */
    public Map<String, Double> table(NodeType type, int bracket, int level) {
        Map<String, Double> vanilla = table(type, bracket);
        if (level <= 100 || !frontierReady()) return vanilla;
        double frontierShare = level <= 110 ? 0.10 : level <= 125 ? 0.20
                : level <= 150 ? 0.30 : level <= 175 ? 0.40 : 0.50;
        Map<String, Double> result = new LinkedHashMap<>();
        double vanillaTotal = vanilla.values().stream().mapToDouble(Double::doubleValue).sum();
        vanilla.forEach((key, weight) ->
                result.put(key, vanillaTotal <= 0 ? 0 : weight / vanillaTotal * (1.0 - frontierShare) * 100));
        List<String> materials = frontierMaterials(type, level);
        double each = frontierShare * 100 / materials.size();
        materials.forEach(material -> result.put(material, each));
        return result;
    }

    public boolean frontierReady() {
        return plugin.getConfig().getBoolean("frontier.enabled", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.profession", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.equipment", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.repair", false)
                && plugin.getConfig().getBoolean("frontier.sink-gates.project", false);
    }

    private List<String> frontierMaterials(NodeType type, int level) {
        List<String> all = switch (type) {
            case MINING -> List.of("AETHER_ORE", "MYTHRIL_FRAGMENT", "RESONANT_CRYSTAL");
            case FARMING -> List.of("MANA_HERB", "LIFE_SEED", "ASTRAL_POLLEN");
            case WOODCUTTING -> List.of("SPIRITWOOD_RESIN", "ANCIENT_BARK", "LIVING_FIBER");
            case LIVESTOCK -> List.of("MYSTIC_HIDE", "BEAST_CORE", "PRIMAL_BLOOD");
            case HUNTER -> List.of("SOUL_ASH", "CORRUPTED_ESSENCE", "VOID_FANG");
            default -> List.of("FRONTIER_FRAGMENT");
        };
        int count = level >= 176 ? 3 : level >= 126 ? 2 : 1;
        return all.subList(0, Math.min(count, all.size()));
    }

    public boolean isCustomMaterial(String id) {
        return id != null && Material.matchMaterial(id) == null;
    }

    public ItemStack customItem(String id, int amount) {
        ItemStack item = new ItemStack(Material.PRISMARINE_SHARD, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(pretty(id), NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(
                Component.text("MMORPG Frontier crafting material", NamedTextColor.GRAY),
                Component.text("Bound to the resource economy; no Credit value", NamedTextColor.DARK_GRAY)));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "frontier_material"),
                PersistentDataType.STRING, id.toUpperCase(Locale.ROOT));
        item.setItemMeta(meta);
        return item;
    }

    private String pretty(String id) {
        String value = id.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Raw editable table at a path ("mining" or "mining.bracket-2"). */
    public Map<String, Double> editable(String path) {
        Map<String, Double> table = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!section.isConfigurationSection(key)) {
                    table.put(key, section.getDouble(key));
                }
            }
        }
        return table;
    }

    public boolean setWeight(String path, String material, double weight) {
        Material parsed = Material.matchMaterial(material);
        if (parsed == null || !parsed.isItem() || !Double.isFinite(weight)) {
            plugin.getLogger().warning("Rejected invalid drop-table edit: " + material + "=" + weight);
            return false;
        }
        String key = path + "." + material.toLowerCase(Locale.ROOT);
        Object previous = yaml.get(key);
        yaml.set(key, weight <= 0 ? null : weight);
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            yaml.set(key, previous);
            plugin.getLogger().warning("Rejected drop-table edit that would invalidate published content: "
                    + errors.getFirst());
            return false;
        }
        backupRevision();
        save();
        return true;
    }

    private void backupRevision() {
        if (!file.exists()) return;
        File directory = new File(plugin.getDataFolder(), "content-revisions");
        directory.mkdirs();
        File revision = new File(directory, "drops-" + System.currentTimeMillis() + ".yml");
        try {
            java.nio.file.Files.copy(file.toPath(), revision.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not create drop-table revision: " + e.getMessage());
        }
    }

    public boolean rollbackLatest() {
        File directory = new File(plugin.getDataFolder(), "content-revisions");
        File[] revisions = directory.listFiles((dir, name) ->
                name.startsWith("drops-") && name.endsWith(".yml"));
        if (revisions == null || revisions.length == 0) return false;
        java.util.Arrays.sort(revisions,
                java.util.Comparator.comparingLong(File::lastModified).reversed());
        try {
            java.nio.file.Files.copy(revisions[0].toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            this.yaml = YamlConfiguration.loadConfiguration(file);
            return validate().isEmpty();
        } catch (IOException e) {
            plugin.getLogger().severe("Drop-table rollback failed: " + e.getMessage());
            return false;
        }
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save drops.yml: " + e.getMessage());
        }
    }
}
