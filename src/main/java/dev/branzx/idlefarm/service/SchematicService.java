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

    /** Builds housing from the node's definition, capturing the snapshot first. */
    public void buildHousing(NodeRecord node, World world) {
        SchematicDefinition definition = registry.forNodeType(node.getType());
        Location origin = origin(node, world);
        List<String> snapshot = new ArrayList<>();
        paste(definition, origin, snapshot);

        snapshots.put(node.getId(), snapshot);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_snapshots (node_id, blocks_json) VALUES (?, ?)")) {
                upsert.setLong(1, node.getId());
                upsert.setString(2, String.join("\n", snapshot));
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist snapshot for node " + node.getId() + ": " + e.getMessage());
            }
        });
    }

    /** Re-paste a damaged building; the original terrain snapshot is untouched. */
    public void rebuild(NodeRecord node, World world) {
        SchematicDefinition definition = registry.forNodeType(node.getType());
        paste(definition, origin(node, world), null);
    }

    private void paste(SchematicDefinition definition, Location origin, List<String> snapshotOut) {
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
            Block block = world.getBlockAt(x, y, z);
            if (snapshotOut != null) {
                snapshotOut.add(x + "," + y + "," + z + "|" + block.getBlockData().getAsString());
            }
            block.setBlockData(plugin.getServer().createBlockData(entry.substring(pipe + 1)), false);
        }
    }

    /** Restores the pre-claim terrain and drops the stored snapshot. */
    public void restoreTerrain(NodeRecord node, World world) {
        List<String> snapshot = snapshots.remove(node.getId());
        if (snapshot != null) {
            for (String line : snapshot) {
                int pipe = line.indexOf('|');
                if (pipe < 0) {
                    continue;
                }
                String[] coords = line.substring(0, pipe).split(",");
                BlockData data = plugin.getServer().createBlockData(line.substring(pipe + 1));
                world.getBlockAt(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]),
                        Integer.parseInt(coords[2])).setBlockData(data, false);
            }
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
