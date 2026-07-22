package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
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

    private final IdlePlugin plugin;
    private final NodeStore nodeStore;
    private final PlayerDataStore playerDataStore;
    private final SchematicService schematicService;
    private final WorkerNpcManager npcManager;
    private final ExplorationService explorationService;
    private final WorkerService workerService;
    private final dev.branzx.idle.storage.WorkerStore workerStore;
    private final GlobalExpeditionService globalExpeditionService;
    private final GameDesignService gameDesignService;
    private final NodeAnchorStore anchorStore;
    private final AuditService auditService;

    public ClaimService(IdlePlugin plugin, NodeStore nodeStore, PlayerDataStore playerDataStore,
                        SchematicService schematicService, WorkerNpcManager npcManager,
                        ExplorationService explorationService, WorkerService workerService,
                        dev.branzx.idle.storage.WorkerStore workerStore,
                        NodeAnchorStore anchorStore, GlobalExpeditionService globalExpeditionService,
                        GameDesignService gameDesignService, AuditService auditService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.playerDataStore = playerDataStore;
        this.schematicService = schematicService;
        this.npcManager = npcManager;
        this.explorationService = explorationService;
        this.workerService = workerService;
        this.workerStore = workerStore;
        this.anchorStore = anchorStore;
        this.globalExpeditionService = globalExpeditionService;
        this.gameDesignService = gameDesignService;
        this.auditService = auditService;
    }

    private dev.branzx.idle.complex.ComplexService complexService;

    /** Late-bound: the complex service is built alongside this one. */
    public void setComplexService(dev.branzx.idle.complex.ComplexService complexService) {
        this.complexService = complexService;
    }

    /**
     * Breaks the Complex a node belongs to before the node stops qualifying.
     * Rendering a Complex with a hole in it is worse than reverting it.
     */
    private NodeRecord complexAnchorBeforeLoss(NodeRecord record) {
        return complexService != null && record.isInComplex()
                ? complexService.anchorOf(record) : null;
    }

    private void releaseComplex(NodeRecord record, NodeRecord anchor, World world) {
        if (complexService != null && record.isInComplex()) {
            complexService.onMemberLost(record, anchor, world);
        }
    }

    private void audit(UUID actor, String action, String detail) {
        if (auditService != null) {
            auditService.log(actor, action, detail);
        }
    }

    public boolean isClaimableWorld(World world) {
        return plugin.getWorldGate().isEnabled(world);
    }

    /**
     * Starting price of a claim, before the escalation in
     * {@link #costAtIndex(NodeType, int)}.
     */
    public double baseClaimCost(NodeType type) {
        if (type == NodeType.RESIDENTIAL) {
            return plugin.getConfig().getDouble("claims.residential-cost", 500.0);
        }
        return plugin.getConfig().getDouble("claims.production-cost", 1000.0);
    }

    private double costFactor(NodeType type) {
        double factor = type == NodeType.RESIDENTIAL
                ? plugin.getConfig().getDouble("claims.residential-cost-factor", 1.15)
                : plugin.getConfig().getDouble("claims.production-cost-factor", 1.6);
        // A factor below 1 would make land get cheaper the more you own,
        // which inverts the whole point of the escalation.
        return Math.max(1.0, factor);
    }

    /**
     * Price of the next claim when the player already owns {@code existing}
     * of that category.
     *
     * <p>Escalating rather than flat: a fixed price makes the widest possible
     * territory the obvious play for anyone with Coins, and removes the
     * decision between going wide and going deep. Rising prices let the
     * market do the limiting instead of a hard cap.</p>
     */
    public double costAtIndex(NodeType type, int existing) {
        return baseClaimCost(type) * Math.pow(costFactor(type), Math.max(0, existing));
    }

    /** Price of this player's next claim of that category. */
    public double claimCost(UUID owner, NodeType type) {
        return costAtIndex(type, ownedOfCategory(owner, type));
    }

    /** How many claims of a type's category the player already holds. */
    public int ownedOfCategory(UUID owner, NodeType type) {
        return type == NodeType.RESIDENTIAL
                ? (int) countResidential(owner)
                : (int) countProductionNodes(owner);
    }

    /**
     * Refund for giving one back: a fraction of what that claim cost, which
     * is the price at the index it occupies rather than the price of the next
     * one. Refunding against the next, higher, price would pay players to
     * churn claims.
     */
    public double unclaimRefund(UUID owner, NodeType type) {
        int existing = Math.max(0, ownedOfCategory(owner, type) - 1);
        return costAtIndex(type, existing)
                * plugin.getConfig().getDouble("claims.unclaim-refund-ratio", 0.5);
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
     * The outcome of claim validation, plus the price. Preview and the actual
     * claim both go through here so a preview can never show terms the commit
     * would not honour.
     */
    public record Quote(boolean allowed, String message, double cost, boolean tutorialFree) {
        static Quote denied(String message) {
            return new Quote(false, message, 0, false);
        }
    }

    /**
     * Runs every claim rule without mutating anything. Safe to call
     * repeatedly; the result is only valid for this instant, so the commit
     * path re-runs it rather than trusting an earlier answer.
     */
    public Quote quote(UUID owner, World world, ChunkKey chunk, NodeType type) {
        if (nodeStore.getByChunk(chunk) != null) {
            return Quote.denied("This chunk is already claimed.");
        }
        // A previous unclaim's terrain restore may still be running here.
        if (schematicService.isBusy(chunk)) {
            return Quote.denied("This chunk is still being restored; try again in a moment.");
        }

        boolean ownerHasResidential = hasResidential(owner);

        if (type == NodeType.RESIDENTIAL) {
            long residential = countResidential(owner);
            int cap = residentialCap(owner);
            if (residential >= cap) {
                return Quote.denied("Residential plot cap reached (" + residential + "/" + cap + ").");
            }
            // Extra residential plots must still connect to the territory;
            // the very first plot may be claimed anywhere.
            if (ownerHasResidential && !isAdjacentToOwn(owner, chunk)) {
                return Quote.denied("Additional plots must touch your existing territory.");
            }
        } else {
            if (!ownerHasResidential) {
                return Quote.denied("Claim a Residential node first (/idle claim residential).");
            }
            if (gameDesignService != null && gameDesignService.firstProductionIsFree(owner)
                    && type != NodeType.MINING && type != NodeType.FARMING
                    && type != NodeType.WOODCUTTING) {
                return Quote.denied("Your free Starter Node can be Mining, Farming, or Woodcutting.");
            }
            if (!isAdjacentToOwn(owner, chunk)) {
                return Quote.denied("Production nodes must touch your existing territory.");
            }
            long production = countProductionNodes(owner);
            int cap = nodeCap(owner);
            if (production >= cap) {
                return Quote.denied("Node cap reached (" + production + "/" + cap + ").");
            }
        }

        boolean tutorialFree = gameDesignService != null
                && (type == NodeType.RESIDENTIAL
                ? gameDesignService.firstResidentialIsFree(owner)
                : gameDesignService.firstProductionIsFree(owner));
        double cost = tutorialFree ? 0 : claimCost(owner, type);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null) {
            return Quote.denied("Your data is still loading, try again in a moment.");
        }
        if (data.getBalance() < cost) {
            return Quote.denied("Not enough money (need " + cost + ").");
        }
        return new Quote(true, "", cost, tutorialFree);
    }

    /**
     * Full claim validation + persistence. Must be called on the main thread:
     * validation and in-memory mutation are synchronous and authoritative,
     * while durability goes through the async DB write queue.
     */
    public Result claim(UUID owner, World world, ChunkKey chunk, NodeType type) {
        return claim(owner, world, chunk, type, -1);
    }

    /**
     * Claims a chunk, optionally reusing a ground level already computed for a
     * placement preview so the building lands exactly where the player saw it.
     * Pass a negative {@code presetOriginY} to compute it fresh.
     */
    public Result claim(UUID owner, World world, ChunkKey chunk, NodeType type, int presetOriginY) {
        // Re-validated at commit time even when a preview already passed:
        // the chunk, the balance, and the caps can all change while a preview
        // is open, so an earlier answer is never trusted.
        Quote quote = quote(owner, world, chunk, type);
        if (!quote.allowed()) {
            return Result.fail(quote.message());
        }
        double cost = quote.cost();
        boolean tutorialFree = quote.tutorialFree();
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null) {
            return Result.fail("Your data is still loading, try again in a moment.");
        }

        int originY;
        if (!type.isProduction()) {
            originY = 0;
        } else if (presetOriginY >= 0) {
            originY = presetOriginY;
        } else {
            originY = schematicService.groundY(world, chunk,
                    schematicService.getRegistry().forNodeType(type, 1));
        }
        NodeRecord record = nodeStore.insert(owner, chunk, type, originY, data, cost);
        if (record == null) {
            return Result.fail("Claim could not be committed; no Coins were charged.");
        }
        String siteNote = "";
        if (type.isProduction()) {
            // Workers spawn once the building actually exists; a large
            // blueprint finishes over the next few ticks.
            SchematicService.SiteReport site = schematicService.buildHousing(record, world,
                    () -> npcManager.spawnForNode(record, world));
            // Vegetation was cleared into the snapshot and returns on unclaim,
            // so it is not worth a warning. Solid blocks were left alone and
            // the owner needs to know the building overlaps them.
            if (!site.isClear()) {
                siteNote = " Note: " + site.obstructions().size()
                        + " solid block(s) overlap the building and were left in place.";
            }
        }
        boolean firstProduction = gameDesignService != null && gameDesignService.onNodeClaimed(record);
        if (firstProduction && workerService != null) {
            workerService.grantStarter(owner);
        }
        audit(owner, "CLAIM", type + " @ " + chunk.world() + " " + chunk.x() + "," + chunk.z()
                + " cost=" + cost + " tutorialFree=" + tutorialFree);
        return Result.ok(type + " node claimed at chunk " + chunk.x() + "," + chunk.z()
                + (tutorialFree ? " (tutorial claim: FREE)." : " (-" + cost + ").") + siteNote);
    }

    public Result unclaim(UUID owner, World world, ChunkKey chunk) {
        NodeRecord record = nodeStore.getByChunk(chunk);
        if (record == null || !record.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this chunk.");
        }
        // The building here is still being written; tearing it down mid-write
        // would leave whatever the in-flight job places afterwards.
        if (schematicService.isBusy(chunk)
                || (complexService != null && record.isInComplex() && complexService.isBusy(record))) {
            return Result.fail("This node's building is still going up; try again in a moment.");
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

        // Spec §6b: buffers must be collected first — no dupe/loss edge cases.
        if (record.getType().isProduction()
                && record.storageTotal() + record.bulkStorageTotal() > 0) {
            return Result.fail("Collect this node's buffer before unclaiming.");
        }
        if (record.getType().isProduction() && globalExpeditionService != null
                && globalExpeditionService.hasCommitments(record.getId())) {
            return Result.fail("Wait for this node's Global Expedition commitment to finish.");
        }

        NodeRecord formerComplexAnchor = complexAnchorBeforeLoss(record);
        boolean restoreResidentialSlice = record.isInComplex()
                && record.getType() == NodeType.RESIDENTIAL;

        double refund = unclaimRefund(owner, record.getType());

        if (record.getType().isProduction()) {
            if (explorationService != null) {
                explorationService.cancel(record); // in-progress event: no loot
            }
            ejectAllWorkers(record); // workers return to roster, never destroyed
            if (anchorStore != null) {
                anchorStore.clearNode(record.getId());
            }
            npcManager.despawnNode(record.getId());
        }
        // The node delete and its refund settle in one transaction; the world
        // is only restored after the durable commit succeeds.
        PlayerData data = playerDataStore.getOnline(owner);
        if (data != null) {
            if (!nodeStore.deleteWithRefund(record, data, refund)) {
                return Result.fail("Unclaim could not be committed; the node is unchanged.");
            }
        } else {
            nodeStore.delete(record);
        }
        // Only mutate Complex membership and the world after the durable
        // unclaim succeeds. Otherwise a failed refund transaction would leave
        // an intact node whose Complex had already been dismantled.
        releaseComplex(record, formerComplexAnchor, world);
        if (record.getType() == NodeType.RESIDENTIAL && gameDesignService != null) {
            gameDesignService.onResidentialRemoved(owner);
        }
        if (record.getType().isProduction() || restoreResidentialSlice) {
            schematicService.restoreTerrain(record, world);
        }
        audit(owner, "UNCLAIM", record.getType() + " @ " + chunk.world() + " " + chunk.x() + ","
                + chunk.z() + " refund=" + refund);
        return Result.ok("Node unclaimed (+" + refund + " refund). Worker contracts returned.");
    }

    /** Moderation seizure: no refund, reason and audit id are mandatory. */
    public Result forceUnclaim(UUID actor, World world, ChunkKey chunk, String reason, String auditId) {
        NodeRecord record = nodeStore.getByChunk(chunk);
        if (record == null) return Result.fail("No claimed node at that chunk.");
        if (reason == null || reason.isBlank() || auditId == null || auditId.isBlank()) {
            return Result.fail("A moderation reason and audit id are required.");
        }
        if (schematicService.isBusy(chunk)
                || (complexService != null && record.isInComplex() && complexService.isBusy(record))) {
            return Result.fail("This building is still being written; try again in a moment.");
        }
        NodeRecord formerComplexAnchor = complexAnchorBeforeLoss(record);
        boolean restoreResidentialSlice = record.isInComplex()
                && record.getType() == NodeType.RESIDENTIAL;
        if (record.getType().isProduction()) {
            if (explorationService != null) explorationService.cancel(record);
            ejectAllWorkers(record);
            if (anchorStore != null) anchorStore.clearNode(record.getId());
            npcManager.despawnNode(record.getId());
        }
        nodeStore.delete(record);
        releaseComplex(record, formerComplexAnchor, world);
        if (record.getType().isProduction() || restoreResidentialSlice) {
            schematicService.restoreTerrain(record, world);
        }
        if (record.getType() == NodeType.RESIDENTIAL && gameDesignService != null) {
            gameDesignService.onResidentialRemoved(record.getOwnerUuid());
        }
        auditService.logAdmin(actor, auditId, reason, "ADMIN_FORCE_UNCLAIM",
                "node=" + record.getId() + " owner=" + record.getOwnerUuid() + " refund=0");
        return Result.ok("Force-unclaimed node #" + record.getId() + " with no refund. Audit " + auditId);
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

    // ---- appearance ----

    /**
     * Rewrites a placed building after its appearance changed (rotation, and
     * later skin). Restores the terrain the old building covered, re-pastes,
     * and resettles the workers.
     *
     * <p>Custom worker anchor overrides are dropped because they were picked
     * against the old layout — the same rule tier upgrades follow (spec
     * §4.5). Keeping them would leave NPCs standing in the new walls.</p>
     */
    private void reapplyBuilding(NodeRecord node, World world) {
        if (anchorStore != null) {
            anchorStore.clearNode(node.getId());
        }
        // A Complex rewrites every one of its chunks, not just the anchor's.
        if (complexService != null && node.isInComplex()) {
            complexService.rebuildComplex(node, world);
            return;
        }
        // Strictly sequential: a large restore and a large build both run
        // across ticks, and overlapping them would have the restore put old
        // terrain back on top of the new building.
        schematicService.restoreTerrain(node, world,
                () -> schematicService.buildHousing(node, world,
                        () -> npcManager.refreshNode(node, world)));
    }

    /**
     * Changes which skin a node wears. Purely cosmetic: nothing about
     * production, workers, or caps changes, which is what keeps skins sellable
     * without touching the power ceiling.
     */
    public Result applySkin(UUID owner, World world, NodeRecord node, String skinId) {
        if (!node.getType().isProduction()) {
            return Result.fail("Only production nodes have a building to reskin.");
        }
        if (!node.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this node.");
        }
        if (node.isUpgrading()) {
            return Result.fail("Wait for this node's upgrade to finish before reskinning.");
        }
        if (complexService != null ? complexService.isBusy(node)
                : schematicService.isBusy(node.getChunk())) {
            return Result.fail("This building is still going up; try again in a moment.");
        }
        String previous = node.getSkinId();
        String target = skinId == null || skinId.isBlank() ? null : skinId;
        if (target != null && !skinFits(node, target)) {
            return Result.fail(node.isInComplex()
                    ? "Choose a Complex skin authored for this building's shape."
                    : "That skin is authored for a Complex, not a single node.");
        }
        if (java.util.Objects.equals(previous, target)) {
            return Result.fail("This node already wears that skin.");
        }
        node.setSkinId(target);
        nodeStore.updateAppearance(node);
        reapplyBuilding(node, world);
        audit(owner, "SKIN", "node#" + node.getId() + " " + previous + " -> " + target);
        return Result.ok(target == null
                ? "Building reset to the default appearance."
                : "Building reskinned to " + target + ".");
    }

    /** Whether a skin's authored footprint matches the node it would dress. */
    public boolean skinFits(NodeRecord node, String skinId) {
        var skin = schematicService.getRegistry().skinDefinition(skinId);
        if (skin == null) {
            return false;
        }
        if (!node.isInComplex()) {
            return !skin.isComplexSkin();
        }
        var shape = complexService == null ? null : complexService.shapeOf(node);
        return shape != null && skin.matchesShape(shape.id());
    }

    public double rotateCost() {
        return plugin.getConfig().getDouble("nodes.rotate-cost", 100.0);
    }

    /**
     * Turns a placed building to a new orientation. The caller supplies the
     * absolute quarter-turn count the player confirmed in a preview.
     */
    public Result rotate(UUID owner, World world, NodeRecord node, int rotation) {
        // A Complex turns as one unit, so a member redirects to its anchor.
        if (complexService != null && node.isInComplex()) {
            NodeRecord anchor = complexService.anchorOf(node);
            if (anchor != null) {
                node = anchor;
            }
        }
        if (!node.getType().isProduction()) {
            return Result.fail("Only production nodes have a building to rotate.");
        }
        if (!node.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this node.");
        }
        // The upgrade is about to replace this building anyway, and rotating
        // mid-build would desync the rising animation from the record.
        if (node.isUpgrading()) {
            return Result.fail("Wait for this node's upgrade to finish before rotating.");
        }
        if (complexService != null ? complexService.isBusy(node)
                : schematicService.isBusy(node.getChunk())) {
            return Result.fail("This building is still going up; try again in a moment.");
        }
        int target = dev.branzx.idle.schematic.Rotation.normalize(rotation);
        if (target == node.getRotation()) {
            return Result.fail("The building already faces that way.");
        }
        // A long Complex cannot turn sideways: the rotated footprint would
        // need chunks arranged the other way, which the player does not own.
        if (complexService != null && node.isInComplex()
                && !complexService.allowedRotations(node).contains(target)) {
            var shape = complexService.shapeOf(node);
            return Result.fail("A " + (shape == null ? "" : shape.id() + " ")
                    + "Complex can only face two ways — your land runs the other direction. "
                    + "Turn it around instead.");
        }
        double cost = rotateCost();
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null) {
            return Result.fail("Your data is still loading, try again in a moment.");
        }
        if (data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }

        List<NodeRecord> appearanceNodes = complexService != null && node.isInComplex()
                ? complexService.members(node) : List.of(node);
        List<Integer> previous = appearanceNodes.stream().map(NodeRecord::getRotation).toList();
        for (NodeRecord member : appearanceNodes) {
            member.setRotation(target);
        }
        if (!nodeStore.updateAppearancesWithCost(appearanceNodes, data, cost)) {
            for (int i = 0; i < appearanceNodes.size(); i++) {
                appearanceNodes.get(i).setRotation(previous.get(i));
            }
            return Result.fail("Rotation could not be committed; no Coins were charged.");
        }
        reapplyBuilding(node, world);
        audit(owner, "ROTATE", "node#" + node.getId() + " " + previous.getFirst() + " -> " + target
                + " cost=" + cost);
        return Result.ok("Building rotated (-" + cost + "). Worker positions updated.");
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
        long previousEndsAt = node.getUpgradeEndsAt();
        node.setUpgradeEndsAt(System.currentTimeMillis() + buildSeconds(node.getTier() + 1) * 1000L);
        if (!nodeStore.updateProductionWithCost(node, data, cost)) {
            node.setUpgradeEndsAt(previousEndsAt);
            return Result.fail("Upgrade could not be committed; no Coins were charged.");
        }
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
            // Building layout changed → drop custom anchor overrides (§4.5).
            if (anchorStore != null) {
                anchorStore.clearNode(node.getId());
            }
            schematicService.animateUpgrade(node, world,
                    () -> npcManager.refreshNode(node, world));
            var owner = org.bukkit.Bukkit.getPlayer(node.getOwnerUuid());
            if (owner != null) {
                owner.sendMessage(net.kyori.adventure.text.Component.text(
                        "[Idle] Your " + node.getType() + " node reached Tier " + node.getTier() + "!",
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
        if (record.storageTotal() + record.bulkStorageTotal() > 0) {
            return Result.fail("Collect this node's buffer before converting.");
        }
        if (schematicService.isBusy(chunk)) {
            return Result.fail("This building is still going up; try again in a moment.");
        }
        if (globalExpeditionService != null && globalExpeditionService.hasCommitments(record.getId())) {
            return Result.fail("Wait for this node's Global Expedition commitment to finish.");
        }
        double cost = plugin.getConfig().getDouble("claims.convert-cost", 750.0);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null || data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }

        NodeRecord formerComplexAnchor = complexAnchorBeforeLoss(record);

        if (explorationService != null) {
            explorationService.cancel(record);
        }
        ejectAllWorkers(record);
        NodeType oldType = record.getType();
        int oldLevel = record.getExplorationLevel();
        long oldExp = record.getExplorationExp();
        record.setType(newType);
        // Tier kept; exploration level halved (configurable) — §8b.
        double keep = plugin.getConfig().getDouble("claims.convert-exploration-keep", 0.5);
        record.setExplorationLevel((int) Math.floor(record.getExplorationLevel() * keep));
        record.setExplorationExp(0);
        // The type change and its Coin cost settle in one transaction.
        if (!nodeStore.updateProductionWithCost(record, data, cost)) {
            record.setType(oldType);
            record.setExplorationLevel(oldLevel);
            record.setExplorationExp(oldExp);
            return Result.fail("Conversion could not be committed; no Coins were charged.");
        }
        // A Complex skin is authored for the old node type. Dismantle it only
        // after the paid conversion has committed, so a DB failure leaves the
        // previous Complex intact.
        releaseComplex(record, formerComplexAnchor, world);
        // Restore-then-build, not a bare re-paste: the new type's building may
        // be smaller than the old one, and pasting over it would leave the
        // previous walls standing around the new hut.
        reapplyBuilding(record, world);
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
