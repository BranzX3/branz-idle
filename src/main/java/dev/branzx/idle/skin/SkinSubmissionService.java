package dev.branzx.idle.skin;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.complex.ComplexShape;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.schematic.RelPos;
import dev.branzx.idle.schematic.SchematicDefinition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a building a player made on land they own into a skin submission.
 *
 * <p>The flow mirrors the admin authoring tools rather than inventing a second
 * one: pick the base Y, mark the worker spawn and work anchors, then capture.
 * The player authors the anchors because they know which doorway is the front
 * of their build; review verifies rather than authors.</p>
 *
 * <p>Standing on a merged Complex submits the whole Complex, sliced into one
 * piece per chunk. Membership already proves the player owns every chunk and
 * that they share one ground level, so a Complex submission needs no separate
 * ownership check.</p>
 *
 * <p>A submission is written to {@code plugins/Idle/skins/pending/} and is
 * inert until an admin approves it. Nothing here can put blocks in front of
 * another player.</p>
 */
public final class SkinSubmissionService {

    /** An in-progress submission: anchors collected before the capture. */
    public static final class Draft {
        private final long nodeId;
        private final Location origin;
        /** Chunks to capture; one for a plain node, several for a Complex. */
        private final List<ChunkKey> chunks;
        /** Null for a single-chunk submission. */
        private final ComplexShape shape;
        private final ChunkKey min;
        private final List<RelPos> spawnAnchors = new ArrayList<>();
        private final List<RelPos> workAnchors = new ArrayList<>();

        Draft(long nodeId, Location origin, List<ChunkKey> chunks, ComplexShape shape,
              ChunkKey min) {
            this.nodeId = nodeId;
            this.origin = origin;
            this.chunks = List.copyOf(chunks);
            this.shape = shape;
            this.min = min;
        }

        public long nodeId() {
            return nodeId;
        }

        public Location origin() {
            return origin;
        }

        public int baseY() {
            return origin.getBlockY();
        }

        public List<ChunkKey> chunks() {
            return chunks;
        }

        public ComplexShape shape() {
            return shape;
        }

        public boolean isComplex() {
            return shape != null;
        }

        public List<RelPos> spawnAnchors() {
            return spawnAnchors;
        }

        public List<RelPos> workAnchors() {
            return workAnchors;
        }

        /** Cell of a chunk inside the shape, as {@code {col, row}}. */
        public int[] cellOf(ChunkKey chunk) {
            return new int[]{chunk.x() - min.x(), chunk.z() - min.z()};
        }
    }

    private final IdlePlugin plugin;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public SkinSubmissionService(IdlePlugin plugin) {
        this.plugin = plugin;
    }

    private File pendingFolder() {
        File folder = new File(new File(plugin.getDataFolder(), "skins"), "pending");
        folder.mkdirs();
        return folder;
    }

    /**
     * Limits for a single-chunk skin: the footprint must fit one chunk while
     * staying centred on its middle column, so it can rotate about it.
     */
    public SkinValidator.Limits limits() {
        var config = plugin.getConfig();
        return new SkinValidator.Limits(
                config.getInt("skins.max-size", 15),
                config.getInt("skins.max-size", 15),
                config.getInt("skins.max-height", 100),
                config.getInt("skins.max-blocks", 6000));
    }

    /**
     * Limits for one piece of a Complex. A piece may fill its whole chunk:
     * the Complex rotates about its own centre, not each chunk's, and a
     * 15-wide piece would leave a one-block seam between chunks.
     */
    public SkinValidator.Limits pieceLimits() {
        var config = plugin.getConfig();
        return new SkinValidator.Limits(16, 16,
                config.getInt("skins.max-height", 100),
                config.getInt("skins.max-blocks", 6000));
    }

    public Draft draft(Player player) {
        return drafts.get(player.getUniqueId());
    }

    public void cancel(Player player) {
        drafts.remove(player.getUniqueId());
    }

    /**
     * Starts a submission anchored at the player's feet. Base Y is taken from
     * where they stand rather than guessed from terrain: the ground around an
     * owned node is theirs, and a wrong guess would slice the build.
     *
     * @param chunks every chunk to capture; a Complex passes all its members
     * @param shape  null for a single-chunk submission
     */
    public Draft begin(Player player, NodeRecord node, List<ChunkKey> chunks, ComplexShape shape) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (ChunkKey chunk : chunks) {
            minX = Math.min(minX, chunk.x());
            minZ = Math.min(minZ, chunk.z());
        }
        Location feet = player.getLocation();
        Location origin = new Location(player.getWorld(),
                (node.getChunk().x() << 4) + 8, feet.getBlockY(), (node.getChunk().z() << 4) + 8);
        Draft draft = new Draft(node.getId(), origin, chunks, shape,
                new ChunkKey(node.getChunk().world(), minX, minZ));
        drafts.put(player.getUniqueId(), draft);
        return draft;
    }

    /** Records the player's current feet position as a slot anchor. */
    public RelPos setAnchor(Player player, Draft draft, int slot, boolean work) {
        Location feet = player.getLocation();
        RelPos pos = new RelPos(feet.getBlockX() - draft.origin().getBlockX(),
                feet.getBlockY() - draft.origin().getBlockY(),
                feet.getBlockZ() - draft.origin().getBlockZ());
        SchematicDefinition.setSlot(work ? draft.workAnchors() : draft.spawnAnchors(),
                slot - 1, pos);
        return pos;
    }

    public record Result(boolean success, String message, List<SkinValidator.Violation> violations) {
    }

    /**
     * Captures the draft's chunks into candidate definitions and validates
     * them.
     *
     * <p>Each capture is strictly chunk-bounded: a submission is built on land
     * that may have neighbours, and a wider box would sweep someone else's
     * wall into a skin that then ships to the whole server.</p>
     */
    public Result submit(Player player, Draft draft, String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (id.isBlank()) {
            return new Result(false, "That name has no usable characters.", List.of());
        }
        if (new File(pendingFolder(), id + ".yml").exists()
                || plugin.getSkinRegistry().get(id) != null) {
            return new Result(false, "A skin named '" + id + "' already exists.", List.of());
        }

        Map<String, SchematicDefinition> pieces = new LinkedHashMap<>();
        List<SkinValidator.Violation> violations = new ArrayList<>();
        var limits = draft.isComplex() ? pieceLimits() : limits();
        int captured = 0;
        for (ChunkKey chunk : draft.chunks()) {
            int[] cell = draft.cellOf(chunk);
            String cellId = cell[0] + "," + cell[1];
            SchematicDefinition piece = capture(player.getWorld(), chunk, draft.baseY(),
                    id + (draft.isComplex() ? "_c" + cell[0] + "_" + cell[1] : ""));
            captured += piece.getBlocks().size();
            // An empty chunk is a courtyard, which is a legitimate part of a
            // Complex — only a wholly empty submission is a mistake.
            if (draft.isComplex() && piece.getBlocks().isEmpty()) {
                pieces.put(cellId, piece);
                continue;
            }
            for (var violation : SkinValidator.validate(piece, limits)) {
                violations.add(draft.isComplex()
                        ? new SkinValidator.Violation(violation.rule(),
                                "chunk " + cellId + " — " + violation.detail())
                        : violation);
            }
            pieces.put(cellId, piece);
        }
        if (captured == 0) {
            violations.add(new SkinValidator.Violation("empty",
                    "nothing was captured — check the block level you are standing on"));
        }
        if (!violations.isEmpty()) {
            return new Result(false, "Submission rejected — fix these and try again:", violations);
        }

        if (!write(id, pieces, player, draft)) {
            return new Result(false, "Could not save the submission; tell an admin.", List.of());
        }
        drafts.remove(player.getUniqueId());
        return new Result(true, "Submitted '" + id + "' for review ("
                + (draft.isComplex() ? draft.shape().id() + " Complex, " : "")
                + captured + " blocks).", List.of());
    }

    /**
     * Scans one chunk from base Y upward. Solid blocks are kept, plus air
     * within a small radius of them so interiors and doorways survive — the
     * same rule the admin capture uses, so submissions and admin captures
     * behave identically when pasted.
     */
    private SchematicDefinition capture(World world, ChunkKey chunk, int baseY, String id) {
        SchematicDefinition definition = new SchematicDefinition(id);
        int height = Math.min(plugin.getConfig().getInt("skins.max-height", 100),
                world.getMaxHeight() - baseY);
        if (height <= 0) {
            return definition;
        }
        int centerX = (chunk.x() << 4) + 8;
        int centerZ = (chunk.z() << 4) + 8;
        int padding = plugin.getConfig().getInt("schematics-capture.air-padding", 2);

        String[][][] data = new String[16][height][16];
        boolean[][][] solid = new boolean[16][height][16];
        for (int dx = -8; dx <= 7; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = -8; dz <= 7; dz++) {
                    var block = world.getBlockAt(centerX + dx, baseY + dy, centerZ + dz);
                    data[dx + 8][dy][dz + 8] = block.getBlockData().getAsString();
                    solid[dx + 8][dy][dz + 8] = !block.getType().isAir();
                }
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < 16; z++) {
                    if (solid[x][y][z] || nearSolid(solid, x, y, z, height, padding)) {
                        definition.getBlocks().add(
                                (x - 8) + "," + y + "," + (z - 8) + "|" + data[x][y][z]);
                    }
                }
            }
        }
        return definition;
    }

    private static boolean nearSolid(boolean[][][] solid, int x, int y, int z, int height, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny >= height) {
                        continue;
                    }
                    if (solid[nx][ny][nz]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean write(String id, Map<String, SchematicDefinition> pieces, Player player,
                          Draft draft) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : pieces.entrySet()) {
            yaml.set("pieces." + entry.getKey() + ".blocks", entry.getValue().getBlocks());
        }
        yaml.set("spawn-anchors", draft.spawnAnchors().stream()
                .map(p -> p == null ? "" : p.serialize()).toList());
        yaml.set("work-anchors", draft.workAnchors().stream()
                .map(p -> p == null ? "" : p.serialize()).toList());
        if (draft.isComplex()) {
            yaml.set("shape", draft.shape().id());
            // Which cell holds the production node, so the layout can be
            // reproduced on any Complex that later wears this skin.
            int[] anchorCell = draft.cellOf(new ChunkKey(draft.origin().getWorld().getName(),
                    draft.origin().getBlockX() >> 4, draft.origin().getBlockZ() >> 4));
            yaml.set("anchor-cell", anchorCell[0] + "," + anchorCell[1]);
        }
        yaml.set("author", player.getUniqueId().toString());
        yaml.set("author-name", player.getName());
        yaml.set("submitted-at", System.currentTimeMillis());
        yaml.set("source-node", draft.nodeId());
        yaml.set("base-y", draft.baseY());
        try {
            yaml.save(new File(pendingFolder(), id + ".yml"));
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save skin submission " + id + ": " + e.getMessage());
            return false;
        }
    }

    /** Ids currently awaiting review. */
    public List<String> pendingIds() {
        File[] files = pendingFolder().listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return List.of();
        }
        return java.util.Arrays.stream(files)
                .map(file -> file.getName().substring(0, file.getName().length() - 4))
                .sorted().toList();
    }

    public File pendingFile(String id) {
        return new File(pendingFolder(), id.toLowerCase(Locale.ROOT) + ".yml");
    }

    // ---- review ----

    /**
     * A submission read back for review.
     *
     * @param pieces cell id ("col,row") -> the blueprint that cell renders
     * @param shape  null for a single-chunk submission
     */
    public record Pending(String id, Map<String, SchematicDefinition> pieces,
                          List<RelPos> spawnAnchors, List<RelPos> workAnchors,
                          ComplexShape shape, String anchorCell, UUID author,
                          String authorName, long submittedAt, int baseY) {

        public boolean isComplex() {
            return shape != null;
        }

        public int blockCount() {
            return pieces.values().stream().mapToInt(p -> p.getBlocks().size()).sum();
        }

        /** The single-chunk blueprint, or the anchor cell's for a Complex. */
        public SchematicDefinition primary() {
            SchematicDefinition anchored = anchorCell == null ? null : pieces.get(anchorCell);
            if (anchored != null) {
                return anchored;
            }
            return pieces.values().stream().findFirst().orElse(null);
        }
    }

    public Pending loadPending(String id) {
        File file = pendingFile(id);
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String normalized = id.toLowerCase(Locale.ROOT);
        ComplexShape shape = ComplexShape.parse(yaml.getString("shape"));

        Map<String, SchematicDefinition> pieces = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("pieces");
        if (section != null) {
            for (String cell : section.getKeys(false)) {
                SchematicDefinition piece = new SchematicDefinition(
                        normalized + (shape == null ? "" : "_c" + cell.replace(',', '_')));
                piece.getBlocks().addAll(section.getStringList(cell + ".blocks"));
                pieces.put(cell, piece);
            }
        }

        List<RelPos> spawnAnchors = new ArrayList<>();
        for (String pos : yaml.getStringList("spawn-anchors")) {
            spawnAnchors.add(pos.isBlank() ? null : RelPos.deserialize(pos));
        }
        List<RelPos> workAnchors = new ArrayList<>();
        for (String pos : yaml.getStringList("work-anchors")) {
            workAnchors.add(pos.isBlank() ? null : RelPos.deserialize(pos));
        }

        UUID author = null;
        String rawAuthor = yaml.getString("author");
        if (rawAuthor != null && !rawAuthor.isBlank()) {
            try {
                author = UUID.fromString(rawAuthor);
            } catch (IllegalArgumentException ignored) {
                // A submission with a corrupt uuid can still be reviewed; the
                // author simply cannot be credited or granted the variant.
            }
        }
        return new Pending(normalized, pieces, spawnAnchors, workAnchors, shape,
                yaml.getString("anchor-cell"), author, yaml.getString("author-name"),
                yaml.getLong("submitted-at"), yaml.getInt("base-y"));
    }

    /** Overwrites the stored anchors after a reviewer adjusts them. */
    public boolean saveAnchors(Pending pending) {
        File file = pendingFile(pending.id());
        if (!file.exists()) {
            return false;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("spawn-anchors", pending.spawnAnchors().stream()
                .map(p -> p == null ? "" : p.serialize()).toList());
        yaml.set("work-anchors", pending.workAnchors().stream()
                .map(p -> p == null ? "" : p.serialize()).toList());
        try {
            yaml.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to update anchors for submission "
                    + pending.id() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Publishes a submission: every piece becomes a schematic and the skin
     * file points at them.
     *
     * <p>The skin ships as a <em>default</em> unlock, which is what makes a
     * contest winner's build available to the whole server. The author's
     * exclusive variant, if any, is granted separately.</p>
     */
    public boolean approve(Pending pending) {
        var schematics = plugin.getSchematicService().getRegistry();
        var skinRegistry = plugin.getSkinRegistry();
        if (skinRegistry == null || pending.pieces().isEmpty()) {
            return false;
        }

        SkinDefinition skin = new SkinDefinition(pending.id());
        skin.setDisplay(pending.id().replace('_', ' '));
        skin.setAuthor(pending.author());
        skin.setAuthorName(pending.authorName());
        skin.setUnlock("default");

        for (var entry : pending.pieces().entrySet()) {
            SchematicDefinition piece = entry.getValue();
            // Anchors belong to the cell the workers actually stand in.
            if (!pending.isComplex() || entry.getKey().equals(pending.anchorCell())) {
                piece.getSpawnAnchors().addAll(pending.spawnAnchors());
                piece.getWorkAnchors().addAll(pending.workAnchors());
            }
            schematics.put(piece);
            schematics.save(piece);
            if (pending.isComplex()) {
                skin.getPieces().put(entry.getKey(), piece.getId());
            } else {
                skin.getTiers().put(1, piece.getId());
            }
        }
        if (pending.isComplex()) {
            skin.setShape(pending.shape().id());
        }
        skinRegistry.put(skin);
        skinRegistry.save(skin);

        return pendingFile(pending.id()).delete();
    }

    public boolean reject(String id) {
        return pendingFile(id).delete();
    }
}
