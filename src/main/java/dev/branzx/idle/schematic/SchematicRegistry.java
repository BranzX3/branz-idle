package dev.branzx.idle.schematic;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeType;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads/saves schematic definitions as YAML files under
 * {@code plugins/Idle/schematics/}. Season content is a file drop.
 */
public final class SchematicRegistry {

    public static final String DEFAULT_ID = "default_hut";

    private final IdlePlugin plugin;
    private final File folder;
    private final Map<String, SchematicDefinition> definitions = new ConcurrentHashMap<>();

    public SchematicRegistry(IdlePlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "schematics");
    }

    public void loadAll() {
        folder.mkdirs();
        definitions.clear();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    SchematicDefinition definition = read(file);
                    definitions.put(definition.getId(), definition);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to load schematic " + file.getName() + ": " + e.getMessage());
                }
            }
        }
        if (!definitions.containsKey(DEFAULT_ID)) {
            SchematicDefinition hut = DefaultHut.build();
            definitions.put(DEFAULT_ID, hut);
            save(hut);
        }
        plugin.getLogger().info("Loaded " + definitions.size() + " schematic definitions.");
    }

    public SchematicDefinition get(String id) {
        return definitions.get(id);
    }

    /** Tier-1 / default definition for a node type. */
    public SchematicDefinition forNodeType(NodeType type) {
        return forNodeType(type, 1);
    }

    /**
     * Definition for a node type at a given tier. Config key
     * {@code schematics.<type>} may be a plain string (used for all tiers)
     * or a section with {@code tier-N} keys (falling back to the highest
     * defined tier at or below the request, then the default hut).
     */
    public SchematicDefinition forNodeType(NodeType type, int tier) {
        String base = "schematics." + type.name().toLowerCase(Locale.ROOT);
        String id = null;
        if (plugin.getConfig().isConfigurationSection(base)) {
            for (int t = tier; t >= 1 && id == null; t--) {
                id = plugin.getConfig().getString(base + ".tier-" + t);
            }
            if (id == null) {
                id = plugin.getConfig().getString(base + ".default");
            }
        } else {
            id = plugin.getConfig().getString(base, DEFAULT_ID);
        }
        SchematicDefinition definition = id == null ? null : definitions.get(id);
        return definition != null ? definition : definitions.get(DEFAULT_ID);
    }

    public void put(SchematicDefinition definition) {
        definitions.put(definition.getId(), definition);
    }

    public List<String> ids() {
        return List.copyOf(definitions.keySet());
    }

    public void save(SchematicDefinition definition) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blocks", definition.getBlocks());
        // Null-safe: unset per-slot anchors serialize as "" and restore as null.
        yaml.set("spawn-anchors", definition.getSpawnAnchors().stream()
                .map(p -> p == null ? "" : p.serialize()).toList());
        yaml.set("work-anchors", definition.getWorkAnchors().stream()
                .map(p -> p == null ? "" : p.serialize()).toList());
        yaml.set("wander-radius", definition.getWanderRadius());
        for (Map.Entry<String, String> entry : definition.getProfiles().entrySet()) {
            yaml.set("profiles." + entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        try {
            yaml.save(new File(folder, definition.getId() + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save schematic " + definition.getId() + ": " + e.getMessage());
        }
    }

    private SchematicDefinition read(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String id = file.getName().substring(0, file.getName().length() - 4);
        SchematicDefinition definition = new SchematicDefinition(id);
        definition.getBlocks().addAll(yaml.getStringList("blocks"));
        for (String pos : yaml.getStringList("spawn-anchors")) {
            definition.getSpawnAnchors().add(pos.isBlank() ? null : RelPos.deserialize(pos));
        }
        for (String pos : yaml.getStringList("work-anchors")) {
            definition.getWorkAnchors().add(pos.isBlank() ? null : RelPos.deserialize(pos));
        }
        definition.setWanderRadius(yaml.getInt("wander-radius", 5));
        var profiles = yaml.getConfigurationSection("profiles");
        if (profiles != null) {
            for (String state : profiles.getKeys(false)) {
                definition.getProfiles().put(state.toUpperCase(Locale.ROOT), profiles.getString(state));
            }
        }
        return definition;
    }
}
