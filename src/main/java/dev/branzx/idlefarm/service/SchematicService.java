package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.schematic.RelPos;
import dev.branzx.idlefarm.schematic.SchematicDefinition;
import dev.branzx.idlefarm.schematic.SchematicRegistry;
import dev.branzx.idlefarm.storage.Database;
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

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final SchematicRegistry registry;
    // nodeId -> serialized pre-build blocks ("x,y,z|blockdata" lines)
    private final Map<Long, List<String>> snapshots = new ConcurrentHashMap<>();

    public SchematicService(IdleFarmPlugin plugin, Database database, SchematicRegistry registry) {
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
                     "SELECT node_id, blocks_json FROM idlefarm_snapshots");
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

    /** Ground Y for a fresh claim at this chunk's center. */
    public static int groundY(World world, ChunkKey chunk) {
        return world.getHighestBlockYAt((chunk.x() << 4) + 8, (chunk.z() << 4) + 8) + 1;
    }

    /** Builds housing from the node's tier definition, capturing the snapshot first. */
    public void buildHousing(NodeRecord node, World world) {
        SchematicDefinition definition = registry.forNodeType(node.getType(), node.getTier());
        Location origin = origin(node, world);
        List<String> snapshot = new ArrayList<>();
        paste(definition, origin, snapshot);
        persistSnapshot(node.getId(), snapshot);
    }

    private void persistSnapshot(long nodeId, List<String> snapshot) {
        snapshots.put(nodeId, snapshot);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_snapshots (node_id, blocks_json) VALUES (?, ?)")) {
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
        SchematicDefinition definition = registry.forNodeType(node.getType(), node.getTier());
        paste(definition, origin(node, world), null);
    }

    /**
     * Rebuilds the housing for the node's CURRENT tier with a layered
     * construction animation (bottom-up). Restores the previous building's
     * terrain snapshot first, then rises the new building a few Y-layers per
     * interval. Used on tier-upgrade completion.
     */
    public void animateUpgrade(NodeRecord node, World world, Runnable onDone) {
        // Restore old terrain (removes the previous building) and re-snapshot
        // fresh terrain for the new building footprint.
        restoreTerrain(node, world);

        SchematicDefinition definition = registry.forNodeType(node.getType(), node.getTier());
        Location origin = origin(node, world);
        List<PasteBlock> structural = new ArrayList<>();
        List<PasteBlock> attachable = new ArrayList<>();
        List<String> snapshot = new ArrayList<>();
        collect(definition, origin, structural, attachable);

        // Snapshot the terrain we're about to overwrite (for a future unclaim).
        for (PasteBlock pb : structural) {
            snapshot.add(pb.x() + "," + pb.y() + "," + pb.z() + "|"
                    + world.getBlockAt(pb.x(), pb.y(), pb.z()).getBlockData().getAsString());
        }
        for (PasteBlock pb : attachable) {
            snapshot.add(pb.x() + "," + pb.y() + "," + pb.z() + "|"
                    + world.getBlockAt(pb.x(), pb.y(), pb.z()).getBlockData().getAsString());
        }
        persistSnapshot(node.getId(), snapshot);

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

    /** Splits a definition into structural + attachable target blocks (no placement). */
    private void collect(SchematicDefinition definition, Location origin,
                         List<PasteBlock> structural, List<PasteBlock> attachable) {
        World world = origin.getWorld();
        for (String entry : definition.getBlocks()) {
            int pipe = entry.indexOf('|');
            if (pipe < 0) {
                continue;
            }
            String[] rel = entry.substring(0, pipe).split(",");
            int x = origin.getBlockX() + Integer.parseInt(rel[0]);
            int y = origin.getBlockY() + Integer.parseInt(rel[1]);
            int z = origin.getBlockZ() + Integer.parseInt(rel[2]);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            BlockData data;
            try {
                data = plugin.getServer().createBlockData(entry.substring(pipe + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            var material = data.getMaterial();
            if (material.isAir() || material.isOccluding()) {
                structural.add(new PasteBlock(x, y, z, data));
            } else {
                attachable.add(new PasteBlock(x, y, z, data));
            }
        }
    }

    private record PasteBlock(int x, int y, int z, BlockData data) {
    }

    /**
     * Two-pass paste (WorldEdit-style stage ordering): air + occluding solids
     * first, then non-occluding "attachables" (doors, torches, signs, crops)
     * once their supports exist. All sets use applyPhysics=false so nothing
     * pops or flows mid-paste; Y is clamped to the world's build limits.
     */
    private void paste(SchematicDefinition definition, Location origin, List<String> snapshotOut) {
        World world = origin.getWorld();
        List<PasteBlock> structural = new ArrayList<>();
        List<PasteBlock> attachable = new ArrayList<>();
        for (String entry : definition.getBlocks()) {
            int pipe = entry.indexOf('|');
            if (pipe < 0) {
                continue;
            }
            String[] rel = entry.substring(0, pipe).split(",");
            int x = origin.getBlockX() + Integer.parseInt(rel[0]);
            int y = origin.getBlockY() + Integer.parseInt(rel[1]);
            int z = origin.getBlockZ() + Integer.parseInt(rel[2]);
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
            var material = data.getMaterial();
            if (material.isAir() || material.isOccluding()) {
                structural.add(new PasteBlock(x, y, z, data));
            } else {
                attachable.add(new PasteBlock(x, y, z, data));
            }
        }
        applyPass(world, structural, snapshotOut);
        applyPass(world, attachable, snapshotOut);
    }

    private void applyPass(World world, List<PasteBlock> blocks, List<String> snapshotOut) {
        for (PasteBlock paste : blocks) {
            Block block = world.getBlockAt(paste.x(), paste.y(), paste.z());
            if (snapshotOut != null) {
                snapshotOut.add(paste.x() + "," + paste.y() + "," + paste.z()
                        + "|" + block.getBlockData().getAsString());
            }
            block.setBlockData(paste.data(), false);
        }
    }

    /** Restores the pre-claim terrain and drops the stored snapshot. */
    public void restoreTerrain(NodeRecord node, World world) {
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
            applyPass(world, structural, null);
            applyPass(world, attachable, null);
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idlefarm_snapshots WHERE node_id = ?")) {
                delete.setLong(1, node.getId());
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to delete snapshot for node " + node.getId() + ": " + e.getMessage());
            }
        });
    }

    /** World location of a definition-relative position for this node. */
    public Location resolve(NodeRecord node, World world, RelPos rel) {
        Location origin = origin(node, world);
        return origin.add(rel.x() + 0.5, rel.y(), rel.z() + 0.5);
    }
}
