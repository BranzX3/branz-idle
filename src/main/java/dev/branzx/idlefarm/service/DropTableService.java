package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
    }

    private void seedFromConfig() {
        YamlConfiguration seed = new YamlConfiguration();
        ConfigurationSection defaults = plugin.getConfig().getConfigurationSection("production.drop-tables");
        if (defaults != null) {
            for (String type : defaults.getKeys(false)) {
                ConfigurationSection typeSection = defaults.getConfigurationSection(type);
                if (typeSection != null) {
                    for (String key : typeSection.getKeys(false)) {
                        Object value = typeSection.get(key);
                        seed.set(type + "." + key, value);
                    }
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

    /**
     * Effective weighted table for a node type at a bracket. If the type has
     * bracket-N subsections, the highest unlocked one wins; a flat section
     * applies to every bracket.
     */
    public Map<String, Double> table(NodeType type, int bracket) {
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        ConfigurationSection section = yaml.getConfigurationSection(typeKey);
        if (section == null) {
            return Map.of("cobblestone", 1.0);
        }
        if (section.getConfigurationSection("bracket-1") != null) {
            ConfigurationSection best = null;
            for (int i = 1; i <= bracket; i++) {
                ConfigurationSection candidate = section.getConfigurationSection("bracket-" + i);
                if (candidate != null) {
                    best = candidate;
                }
            }
            if (best != null) {
                section = best;
            }
        }
        Map<String, Double> table = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            table.put(key, section.getDouble(key));
        }
        return table.isEmpty() ? Map.of("cobblestone", 1.0) : table;
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

    public void setWeight(String path, String material, double weight) {
        yaml.set(path + "." + material.toLowerCase(Locale.ROOT), weight <= 0 ? null : weight);
        save();
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save drops.yml: " + e.getMessage());
        }
    }
}
