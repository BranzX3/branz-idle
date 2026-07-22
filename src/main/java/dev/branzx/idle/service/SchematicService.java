package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.schematic.RelPos;
import dev.branzx.idle.schematic.Rotation;
import dev.branzx.idle.schematic.SchematicDefinition;
import dev.branzx.idle.schematic.SchematicRegistry;
import dev.branzx.idle.storage.Database;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pastes schematic-definition buildings on claim and restores the original
 * terrain on unclaim. The pre-build snapshot is captured per node and
 * persisted so restore survives restarts.
 */
public final class SchematicService {

    private final IdlePlugin plugin;
    private final Database database;
    private final SchematicRegistry registry;
    // nodeId -> serialized pre-build blocks ("x,y,z|blockdata" lines)
    private final Map<Long, List<String>> snapshots = new ConcurrentHashMap<>();
    /** Chunks with a multi-tick build or restore still in flight. */
    private final java.util.Set<ChunkKey> busyChunks = ConcurrentHashMap.newKeySet();

    public SchematicService(IdlePlugin plugin, Database database, SchematicRegistry registry) {
        this.plugin = plugin;
        this.database = database;
        this.registry = registry;
    }

    public SchematicRegistry getRegistry() {
        return registry;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT node_id, blocks_json FROM idle_snapshots");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                snapshots.put(rs.getLong("node_id"), List.of(rs.getString("blocks_json").split("\n")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load terrain snapshots: " + e.getMessage());
        }
    }

    /** Building origin: chunk-center column at the node's stored ground Y. */
    public Location origin(NodeRecord node, World world) {
        ChunkKey chunk = node.getChunk();
        return new Location(world, (chunk.x() << 4) + 8, node.getOriginY(), (chunk.z() << 4) + 8);
    }

    /**
     * Ground Y for a fresh claim, sampled across the definition's footprint.
     *
     * <p>The naive "highest block at the chunk centre" reading treats a tree
     * canopy as ground and puts the whole building on top of it, and a single
     * column cannot tell a flat plot from a slope. Instead every footprint
     * column is probed down past vegetation and liquids to real ground, and
     * the median is used: it ignores a minority of outlier columns (one tree,
     * one ravine cell) where a mean or a max would not.
     */
    public int groundY(World world, ChunkKey chunk, SchematicDefinition definition) {
        return groundY(world, chunk, definition, 0);
    }

    /** Ground level for a rotated footprint; see {@link #groundY(World, ChunkKey, SchematicDefinition)}. */
    public int groundY(World world, ChunkKey chunk, SchematicDefinition definition, int rotation) {
        SchematicDefinition.Bounds bounds = Rotation.rotate(definition.bounds(), rotation);
        int centerX = (chunk.x() << 4) + 8;
        int centerZ = (chunk.z() << 4) + 8;
        List<Integer> samples = new ArrayList<>();
        // Corners and centre always; the interior on a 2-block grid is enough
        // resolution for terrain and keeps large footprints cheap.
        for (int dx = bounds.minX(); dx <= bounds.maxX(); dx += 2) {
            for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz += 2) {
                samples.add(solidGroundY(world, centerX + dx, centerZ + dz));
            }
        }
        samples.add(solidGroundY(world, centerX + bounds.maxX(), centerZ + bounds.maxZ()));
        samples.add(solidGroundY(world, centerX, centerZ));
        java.util.Collections.sort(samples);
        return samples.get(samples.size() / 2) + 1;
    }

    /** Highest solid, non-vegetation, non-liquid block in a column. */
    private int solidGroundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        y = Math.min(y, world.getMaxHeight() - 1);
        while (y > world.getMinHeight()) {
            Block block = world.getBlockAt(x, y, z);
            var material = block.getType();
            // Trunks, canopy, plants and water all sit above the real surface.
            if (!material.isAir() && material.isSolid() && !isVegetation(material)
                    && !block.isLiquid()) {
                break;
            }
            y--;
        }
        return y;
    }

    /**
     * Blocks safe to remove and restore automatically. Vegetation is the
     * overwhelmingly common obstruction and clearing it is fully reversible,
     * because cleared blocks enter the same snapshot the building uses.
     */
    public static boolean isVegetation(org.bukkit.Material material) {
        if (org.bukkit.Tag.LEAVES.isTagged(material)
                || org.bukkit.Tag.LOGS.isTagged(material)
                || org.bukkit.Tag.SAPLINGS.isTagged(material)
                || org.bukkit.Tag.FLOWERS.isTagged(material)
                || org.bukkit.Tag.CROPS.isTagged(material)
                || org.bukkit.Tag.CAVE_VINES.isTagged(material)
                || org.bukkit.Tag.REPLACEABLE.isTagged(material)) {
            return true;
        }
        return switch (material) {
            case BAMBOO, SUGAR_CANE, CACTUS, MOSS_CARPET, MOSS_BLOCK, VINE,
                 PUMPKIN, MELON, BROWN_MUSHROOM_BLOCK, RED_MUSHROOM_BLOCK,
                 MUSHROOM_STEM, HAY_BLOCK -> true;
            default -> false;
        };
    }

    /** What sits in a build volume before the building is written. */
    public record SiteReport(int vegetation, int liquid, List<Block> obstructions) {
        public boolean isClear() {
            return obstructions.isEmpty();
        }
    }

    /**
     * Classifies the build volume. Vegetation and liquids are counted across
     * the whole cleared volume, because that is what actually gets removed.
     *
     * <p>Obstructions are narrower: only solid blocks standing exactly where
     * the building writes a block count, not every solid block inside the
     * clearing volume. A stone hillside beside the wall is not something the
     * player must act on, and reporting it would not match what the preview
     * highlights.</p>
     */
    public SiteReport survey(SchematicDefinition definition, Location origin, int rotation) {
        return survey(definition, origin, rotation, java.util.Set.of());
    }

    /**
     * Survey that ignores a set of positions.
     *
     * <p>Used when re-orienting a placed building: its own walls stand in the
     * build volume but are torn down first, so counting them as obstructions
     * would paint the entire preview red. Note this does not model the
     * terrain that the restore will put back underneath, so ground restored
     * beneath the old building is not counted either.</p>
     */
    public SiteReport survey(SchematicDefinition definition, Location origin, int rotation,
                             java.util.Set<String> ignore) {
        World world = origin.getWorld();
        SchematicDefinition.Bounds bounds = Rotation.rotate(definition.bounds(), rotation);
        java.util.Set<String> occupied = occupiedPositions(definition, origin, rotation);
        int headroom = plugin.getConfig().getInt("nodes.placement.clear-headroom", 6);
        int vegetation = 0;
        int liquid = 0;
        List<Block> obstructions = new ArrayList<>();
        int minY = Math.max(world.getMinHeight(), origin.getBlockY() + bounds.minY());
        int maxY = Math.min(world.getMaxHeight() - 1,
                origin.getBlockY() + bounds.maxY() + headroom);
        for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
            for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(origin.getBlockX() + dx, y,
                            origin.getBlockZ() + dz);
                    var material = block.getType();
                    if (material.isAir()) {
                        continue;
                    }
                    if (isVegetation(material)) {
                        vegetation++;
                    } else if (block.isLiquid()) {
                        liquid++;
                    } else {
                        String key = block.getX() + "," + block.getY() + "," + block.getZ();
                        if (occupied.contains(key) && !ignore.contains(key)) {
                            obstructions.add(block);
                        }
                    }
                }
            }
        }
        return new SiteReport(vegetation, liquid, obstructions);
    }

    /**
     * World positions the definition writes a non-air block to. Shared by the
     * survey and the preview so both agree on what "in the way" means.
     */
    public java.util.Set<String> occupiedPositions(SchematicDefinition definition, Location origin,
                                                   int rotation) {
        java.util.Set<String> occupied = new java.util.HashSet<>();
        for (PasteBlock block : resolveBlocks(definition, origin, rotation)) {
            if (!block.data().getMaterial().isAir()) {
                occupied.add(block.x() + "," + block.y() + "," + block.z());
            }
        }
        return occupied;
    }

    /**
     * Builds housing from the node's blueprint, clearing vegetation and
     * liquids first. Everything removed is captured in the snapshot, so an
     * unclaim restores the trees exactly as they were.
     */
    public SiteReport buildHousing(NodeRecord node, World world) {
        return buildHousing(node, world, null);
    }

    /**
     * Builds housing from the node's blueprint, clearing vegetation and
     * liquids first. Everything removed is captured in the snapshot, so an
     * unclaim restores the trees exactly as they were.
     *
     * <p>The returned report is immediate, but placement may finish over the
     * next few ticks for a large blueprint — pass {@code onDone} for anything
     * that must wait for the finished building, such as spawning workers.</p>
     */
    public SiteReport buildHousing(NodeRecord node, World world, Runnable onDone) {
        SchematicDefinition definition = registry.definitionFor(node);
        Location origin = origin(node, world);
        int rotation = node.getRotation();
        SiteReport report = survey(definition, origin, rotation);
        Snapshot snapshot = new Snapshot();

        // One ordered work list: clearing first (so the snapshot records the
        // real terrain, not the air the clear leaves), then the two paste
        // stages. Splitting these into separate budgeted jobs would let a
        // door be placed before the wall it hangs on.
        List<PasteBlock> work = new ArrayList<>(clearWork(definition, origin, rotation));
        List<PasteBlock> structural = new ArrayList<>();
        List<PasteBlock> attachable = new ArrayList<>();
        collect(resolveBlocks(definition, origin, rotation), structural, attachable);
        work.addAll(structural);
        work.addAll(attachable);

        applyBudgeted(node.getChunk(), world, work, snapshot, () -> {
            persistSnapshot(node.getId(), snapshot.lines());
            if (onDone != null) {
                onDone.run();
            }
        });
        return report;
    }

    /**
     * The vegetation and liquid removals for a build volume, as air
     * placements. Ordered top-down so a cleared trunk never leaves floating
     * canopy part-way through; solid blocks are left alone entirely.
     */
    private List<PasteBlock> clearWork(SchematicDefinition definition, Location origin,
                                       int rotation) {
        World world = origin.getWorld();
        SchematicDefinition.Bounds bounds = Rotation.rotate(definition.bounds(), rotation);
        int headroom = plugin.getConfig().getInt("nodes.placement.clear-headroom", 6);
        BlockData air = plugin.getServer().createBlockData(org.bukkit.Material.AIR);
        List<PasteBlock> work = new ArrayList<>();
        int minY = Math.max(world.getMinHeight(), origin.getBlockY() + bounds.minY());
        int maxY = Math.min(world.getMaxHeight() - 1,
                origin.getBlockY() + bounds.maxY() + headroom);
        for (int y = maxY; y >= minY; y--) {
            for (int dx = bounds.minX(); dx <= bounds.maxX(); dx++) {
                for (int dz = bounds.minZ(); dz <= bounds.maxZ(); dz++) {
                    Block block = world.getBlockAt(origin.getBlockX() + dx, y,
                            origin.getBlockZ() + dz);
                    var material = block.getType();
                    if (material.isAir() || (!isVegetation(material) && !block.isLiquid())) {
                        continue;
                    }
                    work.add(new PasteBlock(block.getX(), block.getY(), block.getZ(), air));
                }
            }
        }
        return work;
    }

    /**
     * Accumulates the pre-build state of every block the plugin overwrites.
     * First write per position wins: site clearing runs before the paste, so
     * it holds the genuine original block while the paste would only see the
     * air the clear left behind.
     */
    private static final class Snapshot {
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private final List<String> lines = new ArrayList<>();

        void record(Block block) {
            String key = block.getX() + "," + block.getY() + "," + block.getZ();
            if (seen.add(key)) {
                lines.add(key + "|" + block.getBlockData().getAsString());
            }
        }

        List<String> lines() {
            return lines;
        }
    }

    private void persistSnapshot(long nodeId, List<String> snapshot) {
        snapshots.put(nodeId, snapshot);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idle_snapshots (node_id, blocks_json) VALUES (?, ?)")) {
                upsert.setLong(1, nodeId);
                upsert.setString(2, String.join("\n", snapshot));
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist snapshot for node " + nodeId + ": " + e.getMessage());
            }
        });
    }

    /** Re-paste a damaged building; the original terrain snapshot is untouched. */
    public void rebuild(NodeRecord node, World world) {
        SchematicDefinition definition = registry.definitionFor(node);
        paste(definition, origin(node, world), node.getRotation(), null);
    }

    /**
     * Rebuilds the housing for the node's CURRENT tier with a layered
     * construction animation (bottom-up). Restores the previous building's
     * terrain snapshot first, then rises the new building a few Y-layers per
     * interval. Used on tier-upgrade completion.
     */
    public void animateUpgrade(NodeRecord node, World world, Runnable onDone) {
        // Restore old terrain (removes the previous building), then build. The
        // restore may span ticks, so the rising build waits for it rather than
        // racing it back over the new walls.
        restoreTerrain(node, world, () -> {
            SchematicDefinition definition = registry.definitionFor(node);
            Location origin = origin(node, world);
            Snapshot snapshot = new Snapshot();
            // Restoring put the original vegetation back, and the new tier may
            // have a larger footprint, so the site is cleared again before the
            // rising build starts.
            applyBudgeted(node.getChunk(), world,
                    clearWork(definition, origin, node.getRotation()), snapshot,
                    () -> riseNewBuilding(node, world, definition, origin, snapshot, onDone));
        });
    }

    /** The bottom-up construction pass, once the site is clear. */
    private void riseNewBuilding(NodeRecord node, World world, SchematicDefinition definition,
                                 Location origin, Snapshot snapshot, Runnable onDone) {
        List<PasteBlock> structural = new ArrayList<>();
        List<PasteBlock> attachable = new ArrayList<>();
        collect(resolveBlocks(definition, origin, node.getRotation()), structural, attachable);

        // Snapshot the terrain we're about to overwrite (for a future unclaim).
        for (PasteBlock pb : structural) {
            snapshot.record(world.getBlockAt(pb.x(), pb.y(), pb.z()));
        }
        for (PasteBlock pb : attachable) {
            snapshot.record(world.getBlockAt(pb.x(), pb.y(), pb.z()));
        }
        persistSnapshot(node.getId(), snapshot.lines());

        // Group structural by absolute Y for a rising build; attachables last.
        java.util.TreeMap<Integer, List<PasteBlock>> byLayer = new java.util.TreeMap<>();
        for (PasteBlock pb : structural) {
            byLayer.computeIfAbsent(pb.y(), k -> new ArrayList<>()).add(pb);
        }
        java.util.Iterator<Integer> layers = byLayer.keySet().iterator();
        long ticksPerLayer = Math.max(1, plugin.getConfig().getLong("nodes.build-ticks-per-layer", 4));

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (!layers.hasNext()) {
                    applyPass(world, attachable, null); // doors/torches once solids exist
                    cancel();
                    if (onDone != null) {
                        onDone.run();
                    }
                    return;
                }
                applyPass(world, byLayer.get(layers.next()), null);
                world.playSound(origin, org.bukkit.Sound.BLOCK_STONE_PLACE, 0.8f, 1.0f);
            }
        }.runTaskTimer(plugin, 0L, ticksPerLayer);
    }

    /** A definition block resolved to world coordinates and rotated state. */
    public record PasteBlock(int x, int y, int z, BlockData data) {
    }

    /**
     * Parses a definition into world-space blocks, applying the rotation.
     *
     * <p>This is the only place definition entries are parsed. Pasting, the
     * upgrade animation, the obstruction survey, and the preview all consume
     * this list, so none of them can disagree about where a rotated building
     * lands or which way its stairs face.</p>
     */
    public List<PasteBlock> resolveBlocks(SchematicDefinition definition, Location origin,
                                          int rotation) {
        World world = origin.getWorld();
        List<PasteBlock> resolved = new ArrayList<>();
        for (String entry : definition.getBlocks()) {
            int pipe = entry.indexOf('|');
            if (pipe < 0) {
                continue;
            }
            String[] rel = entry.substring(0, pipe).split(",");
            if (rel.length < 3) {
                continue;
            }
            int dx, dy, dz;
            try {
                dx = Integer.parseInt(rel[0].trim());
                dy = Integer.parseInt(rel[1].trim());
                dz = Integer.parseInt(rel[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            int y = origin.getBlockY() + dy;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue; // clamp to build limits
            }
            BlockData data;
            try {
                data = plugin.getServer().createBlockData(entry.substring(pipe + 1));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid block data in schematic '"
                        + definition.getId() + "': " + entry.substring(pipe + 1));
                continue;
            }
            resolved.add(new PasteBlock(
                    origin.getBlockX() + Rotation.rotateX(dx, dz, rotation), y,
                    origin.getBlockZ() + Rotation.rotateZ(dx, dz, rotation),
                    Rotation.rotate(data, rotation)));
        }
        return resolved;
    }

    /**
     * Splits resolved blocks into the two paste stages: air and occluding
     * solids first, then non-occluding "attachables" (doors, torches, signs,
     * crops) once their supports exist.
     */
    private void collect(List<PasteBlock> resolved, List<PasteBlock> structural,
                         List<PasteBlock> attachable) {
        for (PasteBlock block : resolved) {
            var material = block.data().getMaterial();
            if (material.isAir() || material.isOccluding()) {
                structural.add(block);
            } else {
                attachable.add(block);
            }
        }
    }

    /**
     * Two-pass paste (WorldEdit-style stage ordering). All sets use
     * applyPhysics=false so nothing pops or flows mid-paste.
     */
    private void paste(SchematicDefinition definition, Location origin, int rotation,
                       Snapshot snapshotOut) {
        List<PasteBlock> structural = new ArrayList<>();
        List<PasteBlock> attachable = new ArrayList<>();
        collect(resolveBlocks(definition, origin, rotation), structural, attachable);
        applyPass(origin.getWorld(), structural, snapshotOut);
        applyPass(origin.getWorld(), attachable, snapshotOut);
    }

    private void applyPass(World world, List<PasteBlock> blocks, Snapshot snapshotOut) {
        for (PasteBlock paste : blocks) {
            place(world, paste, snapshotOut);
        }
    }

    private void place(World world, PasteBlock paste, Snapshot snapshotOut) {
        Block block = world.getBlockAt(paste.x(), paste.y(), paste.z());
        if (snapshotOut != null) {
            snapshotOut.record(block);
        }
        block.setBlockData(paste.data(), false);
    }

    /** Blocks placed per tick once a job is large enough to be spread out. */
    private int pasteBudget() {
        return Math.max(1, plugin.getConfig().getInt("nodes.paste-budget-per-tick", 800));
    }

    /**
     * Writes an ordered block list, spreading the work across ticks when it is
     * large enough to stall the server.
     *
     * <p>A skinned building may be several thousand blocks and a Complex tens
     * of thousands; placing those in one tick is a visible freeze. Jobs at or
     * under the per-tick budget still run synchronously, so the common case —
     * a small hut on claim — keeps its existing immediate behaviour and
     * callers do not gain a tick of latency.</p>
     *
     * <p>The list must already be in application order (site clearing, then
     * structural, then attachables), because ordering is what makes doors and
     * torches land on supports that exist.</p>
     */
    private void applyBudgeted(ChunkKey key, World world, List<PasteBlock> ordered,
                               Snapshot snapshotOut, Runnable onDone) {
        if (ordered.size() <= pasteBudget()) {
            applyPass(world, ordered, snapshotOut);
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        if (key != null) {
            busyChunks.add(key);
        }
        new org.bukkit.scheduler.BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                int end = Math.min(ordered.size(), index + pasteBudget());
                for (; index < end; index++) {
                    place(world, ordered.get(index), snapshotOut);
                }
                if (index >= ordered.size()) {
                    cancel();
                    if (key != null) {
                        busyChunks.remove(key);
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * True while a multi-tick build or restore is still writing this chunk.
     *
     * <p>Claiming or unclaiming mid-job would interleave two sets of writes —
     * an unclaim's restore finishing after a re-claim's build would drop the
     * old terrain on top of the new building.</p>
     */
    public boolean isBusy(ChunkKey chunk) {
        return busyChunks.contains(chunk);
    }

    /** Restores the pre-claim terrain and drops the stored snapshot. */
    public void restoreTerrain(NodeRecord node, World world) {
        restoreTerrain(node, world, null);
    }

    /**
     * Restores the pre-claim terrain, then runs {@code onDone}. Anything that
     * writes to the same chunks afterwards — rebuilding under a new skin or
     * rotation — must go through the callback, because a large restore
     * finishes over several ticks.
     */
    public void restoreTerrain(NodeRecord node, World world, Runnable onDone) {
        List<String> snapshot = snapshots.remove(node.getId());
        if (snapshot != null) {
            List<PasteBlock> structural = new ArrayList<>();
            List<PasteBlock> attachable = new ArrayList<>();
            for (String line : snapshot) {
                int pipe = line.indexOf('|');
                if (pipe < 0) {
                    continue;
                }
                String[] coords = line.substring(0, pipe).split(",");
                int y = Integer.parseInt(coords[1]);
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }
                BlockData data;
                try {
                    data = plugin.getServer().createBlockData(line.substring(pipe + 1));
                } catch (IllegalArgumentException e) {
                    continue; // e.g. block removed in a MC update
                }
                PasteBlock block = new PasteBlock(Integer.parseInt(coords[0]), y,
                        Integer.parseInt(coords[2]), data);
                if (data.getMaterial().isAir() || data.getMaterial().isOccluding()) {
                    structural.add(block);
                } else {
                    attachable.add(block);
                }
            }
            List<PasteBlock> work = new ArrayList<>(structural);
            work.addAll(attachable);
            applyBudgeted(node.getChunk(), world, work, null, onDone);
        } else if (onDone != null) {
            onDone.run();
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idle_snapshots WHERE node_id = ?")) {
                delete.setLong(1, node.getId());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete snapshot for node " + node.getId() + ": " + e.getMessage());
            }
        });
    }

    /**
     * World location of a definition-relative position for this node, with
     * the node's rotation applied — NPC spawn and work anchors must turn with
     * the building or workers end up standing inside walls.
     */
    public Location resolve(NodeRecord node, World world, RelPos rel) {
        RelPos rotated = Rotation.rotate(rel, node.getRotation());
        return origin(node, world).add(rotated.x() + 0.5, rotated.y(), rotated.z() + 0.5);
    }
}
