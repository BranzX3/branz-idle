package dev.branzx.idle.schematic;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeRecord;
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
    private volatile dev.branzx.idle.skin.SkinRegistry skinRegistry;
    private volatile ComplexLayout complexLayout;

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
        installDemoComplex();
        plugin.getLogger().info("Loaded " + definitions.size() + " schematic definitions.");
    }

    /**
     * Writes the sample 2×1 Complex pieces when they are missing, so merging
     * can be tried before any player-authored Complex skin exists. Turn
     * {@code skins.install-demo} off on a server that does not want it, and
     * delete the files.
     */
    private void installDemoComplex() {
        if (!plugin.getConfig().getBoolean("skins.install-demo", true)) {
            return;
        }
        for (SchematicDefinition piece : List.of(DemoComplex.west(), DemoComplex.east())) {
            if (!definitions.containsKey(piece.getId())) {
                definitions.put(piece.getId(), piece);
                save(piece);
            }
        }
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

    /**
     * The single blueprint lookup for a placed node. A node with a skin uses
     * that skin's per-tier definition; everything else falls back to the
     * server default for the node type. Every paste, rebuild, and anchor
     * resolution must go through here so a skin can never be half-applied.
     */
    public SchematicDefinition definitionFor(NodeRecord node) {
        SchematicDefinition piece = complexPiece(node);
        if (piece != null) {
            return piece;
        }
        SchematicDefinition skinned = forSkin(node.getSkinId(), node.getTier());
        return skinned != null ? skinned : forNodeType(node.getType(), node.getTier());
    }

    /**
     * The slice of a Complex building this node's chunk renders.
     *
     * <p>The skin lives on the anchor, so a Residential member resolves
     * through it. A cell the skin leaves undefined renders nothing rather
     * than falling back to a hut — an open courtyard is a legitimate part of
     * a large layout, and a stray hut in the middle of one is not.</p>
     *
     * <p>Returns null when the node is not in a Complex, or its Complex has
     * no Complex-shaped skin, so single-chunk resolution continues.</p>
     */
    private SchematicDefinition complexPiece(NodeRecord node) {
        ComplexLayout layout = complexLayout;
        if (layout == null || !node.isInComplex()) {
            return null;
        }
        NodeRecord anchor = layout.anchorOf(node);
        int[] cell = anchor == null ? null : layout.cellOf(node);
        if (cell == null) {
            return null;
        }
        return pieceForCell(anchor, cell[0], cell[1], anchor.getId() == node.getId());
    }

    /**
     * The blueprint one cell of a Complex renders.
     *
     * <p>Public so a merge preview can draw a Complex that does not exist yet
     * through exactly the same resolution the finished building will use —
     * a preview that disagreed with the result would be worse than none.</p>
     *
     * @param anchorCell whether this cell holds the Production node
     * @return the piece, or null to fall through to single-chunk resolution
     */
    public SchematicDefinition pieceForCell(NodeRecord anchor, int col, int row,
                                            boolean anchorCell) {
        dev.branzx.idle.skin.SkinDefinition skin =
                skinRegistry == null || anchor == null ? null : skinRegistry.get(anchor.getSkinId());
        if (skin == null || !skin.isComplexSkin()) {
            // Merged, but wearing no Complex skin yet: the Production node
            // keeps its ordinary building and the Residential plots stay
            // clear. Falling through would put a default hut on every plot.
            return anchorCell ? null : EMPTY;
        }
        String pieceId = skin.pieceIdFor(col, row);
        SchematicDefinition definition = pieceId == null ? null : definitions.get(pieceId);
        // An undefined cell renders nothing: a courtyard is authored by
        // omission, and a stray hut in the middle of one is not wanted.
        return definition != null ? definition : EMPTY;
    }

    /** Renders nothing; used for Complex cells a skin deliberately leaves open. */
    private static final SchematicDefinition EMPTY = new SchematicDefinition("__empty__");

    /** How a node maps onto its Complex; supplied by the complex service. */
    public interface ComplexLayout {
        /** The Production node anchoring this node's Complex, or null. */
        NodeRecord anchorOf(NodeRecord node);

        /** {@code {col, row}} of this node inside its Complex, or null. */
        int[] cellOf(NodeRecord node);
    }

    /** Late-bound: the complex service is built after this registry. */
    public void setComplexLayout(ComplexLayout complexLayout) {
        this.complexLayout = complexLayout;
    }

    /**
     * Resolves a skin to its blueprint at this tier.
     *
     * <p>Returns null when the skin is unset, unknown, or its schematic file
     * is gone, so the caller falls back to the server default. A skin that
     * disappears must never fail a build — a removed seasonal skin would
     * otherwise break every node still wearing it.</p>
     */
    public SchematicDefinition forSkin(String skinId, int tier) {
        if (skinId == null || skinId.isBlank()) {
            return null;
        }
        if (skinRegistry != null) {
            dev.branzx.idle.skin.SkinDefinition skin = skinRegistry.get(skinId);
            if (skin != null) {
                String definitionId = skin.definitionIdFor(tier);
                if (definitionId != null) {
                    SchematicDefinition definition = definitions.get(definitionId);
                    if (definition != null) {
                        return definition;
                    }
                }
            }
        }
        // A bare schematic id also works as a skin, which keeps admin-authored
        // one-off buildings usable without writing a skin file for each.
        return definitions.get(skinId);
    }

    /**
     * Late-bound: the skin registry is built after this one, and skins point
     * at schematics rather than the other way round.
     */
    public void setSkinRegistry(dev.branzx.idle.skin.SkinRegistry skinRegistry) {
        this.skinRegistry = skinRegistry;
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
