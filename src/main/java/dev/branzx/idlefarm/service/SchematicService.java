package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.storage.Database;
import org.bukkit.Location;
import org.bukkit.Material;
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
 * Places worker housing when a production node is claimed and restores the
 * original terrain on unclaim.
 *
 * v1 ships a code-built placeholder hut; the build/restore contract is kept
 * schematic-shaped so a file-based paster (FAWE .schem) can replace
 * {@link #placeHut} later without touching claim/unclaim flow.
 */
public final class SchematicService {

    private final IdleFarmPlugin plugin;
    private final Database database;
    // nodeId -> serialized pre-build blocks ("x,y,z|blockdata" lines)
    private final Map<Long, List<String>> snapshots = new ConcurrentHashMap<>();

    public SchematicService(IdleFarmPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
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

    /** Builds housing at the chunk center, capturing the terrain snapshot first. */
    public void buildHousing(NodeRecord node, World world) {
        ChunkKey chunk = node.getChunk();
        int centerX = (chunk.x() << 4) + 8;
        int centerZ = (chunk.z() << 4) + 8;
        int baseY = world.getHighestBlockYAt(centerX, centerZ) + 1;

        List<String> snapshot = new ArrayList<>();
        forEachHutBlock(world, centerX, baseY, centerZ, (block, material) -> {
            snapshot.add(block.getX() + "," + block.getY() + "," + block.getZ()
                    + "|" + block.getBlockData().getAsString());
            block.setType(material, false);
        });

        snapshots.put(node.getId(), snapshot);
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "INSERT INTO idlefarm_snapshots (node_id, blocks_json) VALUES (?, ?) "
                                 + "ON DUPLICATE KEY UPDATE blocks_json = VALUES(blocks_json)")) {
                upsert.setLong(1, node.getId());
                upsert.setString(2, String.join("\n", snapshot));
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist snapshot for node " + node.getId() + ": " + e.getMessage());
            }
        });
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

    /** Front-door location where NPCs idle. */
    public Location housingFront(NodeRecord node, World world) {
        ChunkKey chunk = node.getChunk();
        int centerX = (chunk.x() << 4) + 8;
        int centerZ = (chunk.z() << 4) + 8;
        int baseY = world.getHighestBlockYAt(centerX, centerZ + 3) + 1;
        return new Location(world, centerX + 0.5, baseY, centerZ + 3.5);
    }

    private interface HutBlockConsumer {
        void accept(Block block, Material material);
    }

    /**
     * Placeholder hut: 5x5 oak hut, 3 high, slab roof, south-facing door gap.
     */
    private void forEachHutBlock(World world, int cx, int baseY, int cz, HutBlockConsumer consumer) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                // floor
                consumer.accept(world.getBlockAt(cx + dx, baseY - 1, cz + dz), Material.OAK_PLANKS);
                // roof
                consumer.accept(world.getBlockAt(cx + dx, baseY + 3, cz + dz), Material.OAK_SLAB);
                for (int dy = 0; dy <= 2; dy++) {
                    Block block = world.getBlockAt(cx + dx, baseY + dy, cz + dz);
                    boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    boolean doorGap = dx == 0 && dz == 2 && dy <= 1;
                    if (corner) {
                        consumer.accept(block, Material.OAK_LOG);
                    } else if (edge && !doorGap) {
                        consumer.accept(block, Material.OAK_PLANKS);
                    } else {
                        consumer.accept(block, Material.AIR);
                    }
                }
            }
        }
    }
}
