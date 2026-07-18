package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClaimService {

    public record Result(boolean success, String message) {
        static Result ok(String message) {
            return new Result(true, message);
        }

        static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final PlayerDataStore playerDataStore;
    private final SchematicService schematicService;
    private final WorkerNpcManager npcManager;

    public ClaimService(IdleFarmPlugin plugin, NodeStore nodeStore, PlayerDataStore playerDataStore,
                        SchematicService schematicService, WorkerNpcManager npcManager) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.playerDataStore = playerDataStore;
        this.schematicService = schematicService;
        this.npcManager = npcManager;
    }

    public boolean isClaimableWorld(World world) {
        List<String> allowed = plugin.getConfig().getStringList("claims.worlds");
        if (allowed.isEmpty()) {
            return world.getEnvironment() == World.Environment.NORMAL;
        }
        return allowed.contains(world.getName());
    }

    public double claimCost(NodeType type) {
        if (type == NodeType.RESIDENTIAL) {
            return plugin.getConfig().getDouble("claims.residential-cost", 500.0);
        }
        return plugin.getConfig().getDouble("claims.production-cost", 1000.0);
    }

    public int nodeCap(UUID owner) {
        int defaultBase = plugin.getConfig().getInt("claims.base-node-cap", 4);
        return nodeStore.getCap(owner, defaultBase);
    }

    public long countProductionNodes(UUID owner) {
        return nodeStore.getByOwner(owner).stream()
                .filter(n -> n.getType().isProduction())
                .count();
    }

    public boolean hasResidential(UUID owner) {
        return nodeStore.getByOwner(owner).stream()
                .anyMatch(n -> n.getType() == NodeType.RESIDENTIAL);
    }

    /**
     * Full claim validation + persistence. Must be called on the main thread:
     * validation and in-memory mutation are synchronous and authoritative,
     * while durability goes through the async DB write queue.
     */
    public Result claim(UUID owner, World world, ChunkKey chunk, NodeType type) {
        if (nodeStore.getByChunk(chunk) != null) {
            return Result.fail("This chunk is already claimed.");
        }

        boolean ownerHasResidential = hasResidential(owner);

        if (type == NodeType.RESIDENTIAL) {
            // Extra residential plots must still connect to the territory.
            if (ownerHasResidential && !isAdjacentToOwn(owner, chunk)) {
                return Result.fail("Additional plots must touch your existing territory.");
            }
        } else {
            if (!ownerHasResidential) {
                return Result.fail("Claim a Residential node first (/idle claim residential).");
            }
            if (!isAdjacentToOwn(owner, chunk)) {
                return Result.fail("Production nodes must touch your existing territory.");
            }
            long production = countProductionNodes(owner);
            int cap = nodeCap(owner);
            if (production >= cap) {
                return Result.fail("Node cap reached (" + production + "/" + cap + ").");
            }
        }

        double cost = claimCost(type);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null) {
            return Result.fail("Your data is still loading, try again in a moment.");
        }
        if (data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }

        NodeRecord record = nodeStore.insert(owner, chunk, type);
        data.addBalance(-cost);
        if (type.isProduction()) {
            schematicService.buildHousing(record, world);
            npcManager.spawnForNode(record, world);
        }
        return Result.ok(type + " node claimed at chunk " + chunk.x() + "," + chunk.z()
                + " (-" + cost + ").");
    }

    public Result unclaim(UUID owner, World world, ChunkKey chunk) {
        NodeRecord record = nodeStore.getByChunk(chunk);
        if (record == null || !record.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this chunk.");
        }

        List<NodeRecord> owned = nodeStore.getByOwner(owner);
        if (record.getType() == NodeType.RESIDENTIAL) {
            boolean lastResidential = owned.stream()
                    .filter(n -> n.getType() == NodeType.RESIDENTIAL)
                    .count() == 1;
            if (lastResidential && owned.size() > 1) {
                return Result.fail("Unclaim all other nodes before abandoning your last Residential node.");
            }
        }

        if (wouldSplitTerritory(owner, chunk)) {
            return Result.fail("Unclaiming this chunk would split your territory.");
        }

        double refund = claimCost(record.getType())
                * plugin.getConfig().getDouble("claims.unclaim-refund-ratio", 0.5);

        if (record.getType().isProduction()) {
            npcManager.despawnNode(record.getId());
            schematicService.restoreTerrain(record, world);
        }
        nodeStore.delete(record);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data != null) {
            data.addBalance(refund);
        }
        return Result.ok("Node unclaimed (+" + refund + " refund).");
    }

    private boolean isAdjacentToOwn(UUID owner, ChunkKey chunk) {
        for (ChunkKey neighbor : chunk.neighbors()) {
            NodeRecord record = nodeStore.getByChunk(neighbor);
            if (record != null && record.getOwnerUuid().equals(owner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * BFS over the owner's remaining nodes: after removing {@code removed},
     * every remaining node must still be reachable from any one of them.
     */
    private boolean wouldSplitTerritory(UUID owner, ChunkKey removed) {
        List<NodeRecord> owned = nodeStore.getByOwner(owner);
        Set<ChunkKey> remaining = new HashSet<>();
        for (NodeRecord node : owned) {
            if (!node.getChunk().equals(removed)) {
                remaining.add(node.getChunk());
            }
        }
        if (remaining.size() <= 1) {
            return false;
        }

        ChunkKey start = remaining.iterator().next();
        Set<ChunkKey> visited = new HashSet<>();
        Deque<ChunkKey> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            ChunkKey current = queue.poll();
            for (ChunkKey neighbor : current.neighbors()) {
                if (remaining.contains(neighbor) && visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return visited.size() != remaining.size();
    }
}
