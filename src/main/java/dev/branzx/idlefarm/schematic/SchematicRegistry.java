package dev.branzx.idlefarm.schematic;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeType;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads/saves schematic definitions as YAML files under
 * {@code plugins/IdleFarm/schematics/}. Season content is a file drop.
 */
public final class SchematicRegistry {

    public static final String DEFAULT_ID = "default_hut";

    private final IdleFarmPlugin plugin;
    private final File folder;
    private final Map<String, SchematicDefinition> definitions = new ConcurrentHashMap<>();

    public SchematicRegistry(IdleFarmPlugin plugin) {
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

    /** Definition for a node type: config mapping, else the default hut. */
    public SchematicDefinition forNodeType(NodeType type) {
        String id = plugin.getConfig().getString(
                "schematics." + type.name().toLowerCase(Locale.ROOT), DEFAULT_ID);
        SchematicDefinition definition = definitions.get(id);
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
        yaml.set("spawn-anchors", definition.getSpawnAnchors().stream().map(RelPos::serialize).toList());
        yaml.set("work-anchors", definition.getWorkAnchors().stream().map(RelPos::serialize).toList());
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
            definition.getSpawnAnchors().add(RelPos.deserialize(pos));
        }
        for (String pos : yaml.getStringList("work-anchors")) {
            definition.getWorkAnchors().add(RelPos.deserialize(pos));
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
