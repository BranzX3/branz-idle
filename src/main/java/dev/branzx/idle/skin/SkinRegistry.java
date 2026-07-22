package dev.branzx.idle.skin;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeType;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads skin files from {@code plugins/Idle/skins/}. Like schematics, a skin
 * is a file drop: approving a contest entry writes a file here, and seasonal
 * content ships the same way.
 */
public final class SkinRegistry {

    private final IdlePlugin plugin;
    private final File folder;
    private final Map<String, SkinDefinition> skins = new ConcurrentHashMap<>();

    public SkinRegistry(IdlePlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "skins");
    }

    public void loadAll() {
        folder.mkdirs();
        skins.clear();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                try {
                    SkinDefinition skin = read(file);
                    skins.put(skin.getId().toLowerCase(Locale.ROOT), skin);
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to load skin " + file.getName()
                            + ": " + e.getMessage());
                }
            }
        }
        installDemoComplex();
        plugin.getLogger().info("Loaded " + skins.size() + " building skins.");
    }

    /**
     * Ships the sample 2×1 Complex skin when missing, matched to the pieces
     * the schematic registry generates. It unlocks by default so it can be
     * tried without an admin grant; turn {@code skins.install-demo} off and
     * delete the file on a server that does not want it.
     */
    private void installDemoComplex() {
        String id = dev.branzx.idle.schematic.DemoComplex.ID;
        if (!plugin.getConfig().getBoolean("skins.install-demo", true)
                || skins.containsKey(id)) {
            return;
        }
        SkinDefinition demo = new SkinDefinition(id);
        demo.setDisplay("Demo Lodge (2x1)");
        demo.setShape("2x1");
        demo.getPieces().put("0,0", id + "_c0_0");
        demo.getPieces().put("1,0", id + "_c1_0");
        demo.setUnlock("default");
        skins.put(id, demo);
        save(demo);
    }

    public SkinDefinition get(String id) {
        return id == null ? null : skins.get(id.toLowerCase(Locale.ROOT));
    }

    public List<SkinDefinition> all() {
        return List.copyOf(skins.values());
    }

    /** Skins that may be worn by this node type, in display order. */
    public List<SkinDefinition> forType(NodeType type) {
        List<SkinDefinition> result = new ArrayList<>();
        for (SkinDefinition skin : skins.values()) {
            if (skin.appliesTo(type)) {
                result.add(skin);
            }
        }
        result.sort(java.util.Comparator.comparing(SkinDefinition::getDisplay,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public void put(SkinDefinition skin) {
        skins.put(skin.getId().toLowerCase(Locale.ROOT), skin);
    }

    public void save(SkinDefinition skin) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("display", skin.getDisplay());
        if (skin.getAuthor() != null) {
            yaml.set("author", skin.getAuthor().toString());
        }
        if (skin.getAuthorName() != null) {
            yaml.set("author-name", skin.getAuthorName());
        }
        yaml.set("node-types", skin.getNodeTypes().stream().map(Enum::name).toList());
        for (Map.Entry<Integer, String> entry : skin.getTiers().entrySet()) {
            yaml.set("tier-" + entry.getKey(), entry.getValue());
        }
        yaml.set("unlock", skin.getUnlock());
        if (skin.getShape() != null) {
            yaml.set("shape", skin.getShape());
            for (Map.Entry<String, String> piece : skin.getPieces().entrySet()) {
                yaml.set("pieces." + piece.getKey(), piece.getValue());
            }
        }
        if (skin.getWinnerVariant() != null) {
            yaml.set("winner-variant", skin.getWinnerVariant());
        }
        try {
            yaml.save(new File(folder, skin.getId() + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save skin " + skin.getId() + ": " + e.getMessage());
        }
    }

    private SkinDefinition read(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String id = file.getName().substring(0, file.getName().length() - 4);
        SkinDefinition skin = new SkinDefinition(id);
        skin.setDisplay(yaml.getString("display"));
        String author = yaml.getString("author");
        if (author != null && !author.isBlank()) {
            try {
                skin.setAuthor(UUID.fromString(author));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skin " + id + " has an invalid author uuid.");
            }
        }
        skin.setAuthorName(yaml.getString("author-name"));
        for (String type : yaml.getStringList("node-types")) {
            NodeType parsed = NodeType.fromString(type);
            if (parsed != null) {
                skin.getNodeTypes().add(parsed);
            }
        }
        // tier-N keys; a skin with only tier-1 wears one building at every tier.
        for (String key : yaml.getKeys(false)) {
            if (!key.startsWith("tier-")) {
                continue;
            }
            try {
                skin.getTiers().put(Integer.parseInt(key.substring(5)), yaml.getString(key));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Skin " + id + " has an invalid tier key: " + key);
            }
        }
        skin.setUnlock(yaml.getString("unlock", "default"));
        skin.setWinnerVariant(yaml.getString("winner-variant"));
        skin.setShape(yaml.getString("shape"));
        var pieces = yaml.getConfigurationSection("pieces");
        if (pieces != null) {
            for (String cell : pieces.getKeys(false)) {
                skin.getPieces().put(cell, pieces.getString(cell));
            }
        }
        return skin;
    }
}
