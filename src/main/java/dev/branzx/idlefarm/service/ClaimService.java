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
    private ExplorationService explorationService;
    private WorkerService workerService;
    private dev.branzx.idlefarm.storage.WorkerStore workerStore;

    public ClaimService(IdleFarmPlugin plugin, NodeStore nodeStore, PlayerDataStore playerDataStore,
                        SchematicService schematicService, WorkerNpcManager npcManager) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.playerDataStore = playerDataStore;
        this.schematicService = schematicService;
        this.npcManager = npcManager;
    }

    private AuditService auditService;

    public void setLateServices(ExplorationService explorationService, WorkerService workerService,
                                dev.branzx.idlefarm.storage.WorkerStore workerStore) {
        this.explorationService = explorationService;
        this.workerService = workerService;
        this.workerStore = workerStore;
    }

    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }

    private void audit(UUID actor, String action, String detail) {
        if (auditService != null) {
            auditService.log(actor, action, detail);
        }
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

    public long countResidential(UUID owner) {
        return nodeStore.getByOwner(owner).stream()
                .filter(n -> n.getType() == NodeType.RESIDENTIAL)
                .count();
    }

    public int residentialCap(UUID owner) {
        return plugin.getConfig().getInt("claims.residential-cap", 3);
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
            long residential = countResidential(owner);
            int cap = residentialCap(owner);
            if (residential >= cap) {
                return Result.fail("Residential plot cap reached (" + residential + "/" + cap + ").");
            }
            // Extra residential plots must still connect to the territory;
            // the very first plot may be claimed anywhere.
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

        int originY = type.isProduction() ? SchematicService.groundY(world, chunk) : 0;
        NodeRecord record = nodeStore.insert(owner, chunk, type, originY);
        data.addBalance(-cost);
        if (type.isProduction()) {
            schematicService.buildHousing(record, world);
            npcManager.spawnForNode(record, world);
        }
        audit(owner, "CLAIM", type + " @ " + chunk.world() + " " + chunk.x() + "," + chunk.z()
                + " cost=" + cost);
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

        // Spec §6b: buffer must be collected first — no dupe/loss edge cases.
        if (record.getType().isProduction() && record.storageTotal() > 0) {
            return Result.fail("Collect this node's buffer before unclaiming.");
        }

        double refund = claimCost(record.getType())
                * plugin.getConfig().getDouble("claims.unclaim-refund-ratio", 0.5);

        if (record.getType().isProduction()) {
            if (explorationService != null) {
                explorationService.cancel(record); // in-progress event: no loot
            }
            ejectAllWorkers(record); // workers return to roster, never destroyed
            npcManager.despawnNode(record.getId());
            schematicService.restoreTerrain(record, world);
        }
        nodeStore.delete(record);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data != null) {
            data.addBalance(refund);
        }
        audit(owner, "UNCLAIM", record.getType() + " @ " + chunk.world() + " " + chunk.x() + ","
                + chunk.z() + " refund=" + refund);
        return Result.ok("Node unclaimed (+" + refund + " refund). Worker contracts returned.");
    }

    /**
     * Ejected workers return to the owner's bag; on bag overflow they become
     * items handed to the online owner (or dropped at the node). Unclaim/
     * convert must never destroy player-owned workers.
     */
    private void ejectAllWorkers(NodeRecord record) {
        if (workerStore == null || workerService == null) {
            return;
        }
        UUID ownerId = record.getOwnerUuid();
        var owner = org.bukkit.Bukkit.getPlayer(ownerId);
        for (var worker : workerStore.getAssigned(record.getId())) {
            var result = workerService.eject(ownerId, worker);
            if (result.item() == null) {
                continue; // settled into the bag
            }
            if (owner != null && owner.isOnline()) {
                var leftover = owner.getInventory().addItem(result.item());
                for (var overflow : leftover.values()) {
                    owner.getWorld().dropItemNaturally(owner.getLocation(), overflow);
                }
            } else {
                World world = org.bukkit.Bukkit.getWorld(record.getChunk().world());
                if (world != null) {
                    world.dropItemNaturally(schematicService.origin(record, world), result.item());
                }
            }
        }
    }

    // ---- timed tier upgrades ----

    public double tierCost(int currentTier) {
        double base = plugin.getConfig().getDouble("nodes.tier-base-cost", 1000);
        double factor = plugin.getConfig().getDouble("nodes.tier-cost-factor", 1.8);
        return base * Math.pow(factor, currentTier - 1);
    }

    public int maxTier() {
        return plugin.getConfig().getInt("nodes.max-tier", 5);
    }

    public long buildSeconds(int targetTier) {
        long base = plugin.getConfig().getLong("nodes.build-seconds-base", 60);
        return base * targetTier;
    }

    /** Begins a timed tier upgrade; production keeps running at the old tier. */
    public Result startUpgrade(UUID owner, NodeRecord node) {
        if (!node.getType().isProduction()) {
            return Result.fail("Only production nodes can be upgraded.");
        }
        if (node.isUpgrading()) {
            return Result.fail("This node is already upgrading.");
        }
        if (node.getTier() >= maxTier()) {
            return Result.fail("Already at max tier (" + maxTier() + ").");
        }
        double cost = tierCost(node.getTier());
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null || data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }
        data.addBalance(-cost);
        node.setUpgradeEndsAt(System.currentTimeMillis() + buildSeconds(node.getTier() + 1) * 1000L);
        nodeStore.updateProduction(node);
        audit(owner, "UPGRADE_START", "node#" + node.getId() + " -> T" + (node.getTier() + 1)
                + " cost=" + cost);
        return Result.ok("Upgrade to T" + (node.getTier() + 1) + " started ("
                + buildSeconds(node.getTier() + 1) + "s).");
    }

    /** Completes any due upgrades. Call on a repeating main-thread tick. */
    public void tickUpgrades() {
        long now = System.currentTimeMillis();
        for (NodeRecord node : nodeStore.getAll()) {
            if (!node.isUpgrading() || now < node.getUpgradeEndsAt()) {
                continue;
            }
            World world = org.bukkit.Bukkit.getWorld(node.getChunk().world());
            // Defer completion until the chunk is loaded, so the build
            // animation always plays and the building never desyncs from tier.
            if (world == null || !world.isChunkLoaded(node.getChunk().x(), node.getChunk().z())) {
                continue;
            }
            node.setUpgradeEndsAt(0);
            node.setTier(node.getTier() + 1);
            nodeStore.updateProduction(node);
            schematicService.animateUpgrade(node, world,
                    () -> npcManager.refreshNode(node, world));
            var owner = org.bukkit.Bukkit.getPlayer(node.getOwnerUuid());
            if (owner != null) {
                owner.sendMessage(net.kyori.adventure.text.Component.text(
                        "[IdleFarm] Your " + node.getType() + " node reached Tier " + node.getTier() + "!",
                        net.kyori.adventure.text.format.NamedTextColor.GREEN));
            }
        }
    }

    /** Convert a production node's type in place (spec §8b). */
    public Result convert(UUID owner, World world, ChunkKey chunk, NodeType newType) {
        NodeRecord record = nodeStore.getByChunk(chunk);
        if (record == null || !record.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this chunk.");
        }
        if (!record.getType().isProduction() || !newType.isProduction()) {
            return Result.fail("Only production nodes can be converted.");
        }
        if (record.getType() == newType) {
            return Result.fail("This node is already " + newType + ".");
        }
        if (record.storageTotal() > 0) {
            return Result.fail("Collect this node's buffer before converting.");
        }
        double cost = plugin.getConfig().getDouble("claims.convert-cost", 750.0);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null || data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }
        data.addBalance(-cost);

        if (explorationService != null) {
            explorationService.cancel(record);
        }
        ejectAllWorkers(record);
        record.setType(newType);
        // Tier kept; exploration level halved (configurable) — §8b.
        double keep = plugin.getConfig().getDouble("claims.convert-exploration-keep", 0.5);
        record.setExplorationLevel((int) Math.floor(record.getExplorationLevel() * keep));
        record.setExplorationExp(0);
        nodeStore.updateProduction(record);
        schematicService.rebuild(record, world);
        npcManager.refreshNode(record, world);
        audit(owner, "CONVERT", "node#" + record.getId() + " -> " + newType + " cost=" + cost);
        return Result.ok("Node converted to " + newType + " (-" + cost
                + "). Worker contracts returned — reassign them.");
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
