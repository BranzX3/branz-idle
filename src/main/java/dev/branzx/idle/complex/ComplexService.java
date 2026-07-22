package dev.branzx.idle.complex;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.SchematicService;
import dev.branzx.idle.service.WorkerNpcManager;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Merges a Production node with the Residential plots around it so one
 * building can span several chunks.
 *
 * <p>Only the Production node produces. Merging changes what is drawn and
 * nothing else — no output, buffer, worker, or rare-cap effect — which is the
 * invariant that keeps the number of Complexes safely uncapped. See
 * {@code docs/NODE_MERGE_AND_COMPLEX.md} §3.</p>
 */
public final class ComplexService {

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
    private final AuditService auditService;

    public ComplexService(IdlePlugin plugin, NodeStore nodeStore, PlayerDataStore playerDataStore,
                          SchematicService schematicService, WorkerNpcManager npcManager,
                          AuditService auditService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.playerDataStore = playerDataStore;
        this.schematicService = schematicService;
        this.npcManager = npcManager;
        this.auditService = auditService;
    }

    // ---- queries ----

    /** Members of a node's Complex, anchor first; empty when it stands alone. */
    public List<NodeRecord> members(NodeRecord node) {
        if (!node.isInComplex()) {
            return List.of();
        }
        List<NodeRecord> members = new ArrayList<>();
        NodeRecord anchor = nodeStore.getById(node.getComplexAnchor());
        if (anchor != null) {
            members.add(anchor);
        }
        for (NodeRecord candidate : nodeStore.getByOwner(node.getOwnerUuid())) {
            if (candidate.getComplexAnchor() == node.getComplexAnchor()
                    && candidate.getId() != node.getComplexAnchor()) {
                members.add(candidate);
            }
        }
        return members;
    }

    /** The Production node anchoring this node's Complex, or null. */
    public NodeRecord anchorOf(NodeRecord node) {
        return node.isInComplex() ? nodeStore.getById(node.getComplexAnchor()) : null;
    }

    /**
     * The shape a formed Complex occupies, derived from its members' chunks
     * rather than stored. A stored shape could drift out of step with the
     * chunks actually held; the chunks are the truth.
     */
    public ComplexShape shapeOf(NodeRecord node) {
        List<NodeRecord> members = members(node);
        if (members.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (NodeRecord member : members) {
            minX = Math.min(minX, member.getChunk().x());
            maxX = Math.max(maxX, member.getChunk().x());
            minZ = Math.min(minZ, member.getChunk().z());
            maxZ = Math.max(maxZ, member.getChunk().z());
        }
        return new ComplexShape(maxX - minX + 1, maxZ - minZ + 1);
    }

    /** Shapes this Production node could form from the land around it. */
    public List<ComplexPlanner.Placement> options(NodeRecord anchor) {
        if (!anchor.getType().isProduction() || anchor.isInComplex()) {
            return List.of();
        }
        return ComplexPlanner.options(anchor.getChunk(),
                chunk -> isFreeResidential(anchor.getOwnerUuid(), chunk));
    }

    /**
     * A chunk usable as Complex floor space: the owner's own Residential plot,
     * not already spent on another Complex, and not mid-rebuild.
     */
    private boolean isFreeResidential(UUID owner, ChunkKey chunk) {
        NodeRecord node = nodeStore.getByChunk(chunk);
        return node != null
                && node.getType() == NodeType.RESIDENTIAL
                && node.getOwnerUuid().equals(owner)
                && !node.isInComplex()
                && !schematicService.isBusy(chunk);
    }

    public double mergeCost(ComplexShape shape) {
        double perChunk = plugin.getConfig().getDouble("complex.cost-per-chunk", 5000.0);
        return perChunk * shape.residentialNeeded();
    }

    // ---- mutation ----

    /**
     * Forms a Complex. The anchor keeps producing exactly as before; the
     * Residential plots contribute their chunk as floor space.
     */
    public Result merge(UUID owner, World world, NodeRecord anchor, ComplexShape shape) {
        if (!plugin.getConfig().getBoolean("complex.enabled", true)) {
            return Result.fail("Complexes are disabled on this server.");
        }
        if (!anchor.getType().isProduction()) {
            return Result.fail("Only a production node can anchor a Complex.");
        }
        if (!anchor.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this node.");
        }
        if (anchor.isInComplex()) {
            return Result.fail("This node is already part of a Complex.");
        }
        if (anchor.isUpgrading()) {
            return Result.fail("Wait for this node's upgrade to finish before merging.");
        }
        if (busyAnywhere(List.of(anchor.getChunk()))) {
            return Result.fail("This building is still going up; try again in a moment.");
        }

        ComplexPlanner.Placement placement = ComplexPlanner.firstFor(shape, anchor.getChunk(),
                chunk -> isFreeResidential(owner, chunk));
        if (placement == null) {
            return Result.fail("You do not own the Residential plots a " + shape.id()
                    + " Complex needs (" + shape.residentialNeeded() + " adjacent).");
        }
        if (busyAnywhere(placement.chunks())) {
            return Result.fail("One of those plots is still being rebuilt; try again shortly.");
        }
        var wornSkin = schematicService.getRegistry().skinDefinition(anchor.getSkinId());
        if (wornSkin != null && wornSkin.isComplexSkin() && !wornSkin.matchesShape(shape.id())) {
            return Result.fail("That skin was authored for a " + wornSkin.getShape()
                    + " Complex, not " + shape.id() + ". Choose a matching skin first.");
        }

        double cost = mergeCost(shape);
        PlayerData data = playerDataStore.getOnline(owner);
        if (data == null) {
            return Result.fail("Your data is still loading, try again in a moment.");
        }
        if (data.getBalance() < cost) {
            return Result.fail("Not enough money (need " + cost + ").");
        }

        List<NodeRecord> supports = new ArrayList<>();
        for (ChunkKey chunk : placement.supportChunks()) {
            NodeRecord support = nodeStore.getByChunk(chunk);
            if (support == null) {
                return Result.fail("That land changed while you were deciding; try again.");
            }
            supports.add(support);
        }

        // Stage all membership in memory, then persist every member and the
        // charge in one transaction. A crash can never leave a paid anchor
        // waiting for queued support-row writes.
        anchor.setComplexAnchor(anchor.getId());
        List<Integer> previousOrigins = supports.stream().map(NodeRecord::getOriginY).toList();
        for (NodeRecord support : supports) {
            support.setComplexAnchor(anchor.getId());
            support.setOriginY(anchor.getOriginY());
        }
        List<NodeRecord> changed = new ArrayList<>();
        changed.add(anchor);
        changed.addAll(supports);
        if (!nodeStore.updateAppearancesWithCost(changed, data, cost)) {
            anchor.setComplexAnchor(0);
            for (int i = 0; i < supports.size(); i++) {
                supports.get(i).setComplexAnchor(0);
                supports.get(i).setOriginY(previousOrigins.get(i));
            }
            return Result.fail("Merge could not be committed; no Coins were charged.");
        }

        rebuildAll(anchor, world);
        audit(owner, "COMPLEX_MERGE", "anchor#" + anchor.getId() + " shape=" + shape.id()
                + " chunks=" + placement.chunks().size() + " cost=" + cost);
        return Result.ok("Merged into a " + shape.id() + " Complex (-" + cost
                + "). The building spans " + shape.blockWidth() + "×" + shape.blockDepth()
                + " blocks.");
    }

    /**
     * Breaks a Complex apart, restoring every plot. Residential plots go back
     * to being plain building space with whatever the player had made there,
     * because each chunk kept its own terrain snapshot.
     */
    public Result unmerge(UUID owner, World world, NodeRecord node) {
        if (!node.isInComplex()) {
            return Result.fail("This node is not part of a Complex.");
        }
        if (!node.getOwnerUuid().equals(owner)) {
            return Result.fail("You do not own this node.");
        }
        NodeRecord anchor = anchorOf(node);
        if (anchor == null) {
            // The anchor is gone; release this plot rather than stranding it.
            node.setComplexAnchor(0);
            nodeStore.updateAppearance(node);
            return Result.ok("Released this plot from a Complex whose anchor no longer exists.");
        }
        List<NodeRecord> members = members(anchor);
        if (busyAnywhere(members.stream().map(NodeRecord::getChunk).toList())) {
            return Result.fail("The Complex is still being built; try again in a moment.");
        }

        for (NodeRecord member : members) {
            member.setComplexAnchor(0);
            nodeStore.updateAppearance(member);
            if (member.getType() != NodeType.RESIDENTIAL) {
                continue;
            }
            // Residential plots hold no building of their own once released.
            schematicService.restoreTerrain(member, world);
        }
        rebuildAll(anchor, world);
        audit(owner, "COMPLEX_UNMERGE", "anchor#" + anchor.getId()
                + " members=" + members.size());
        return Result.ok("Complex broken up. Your Residential plots are yours to build on again.");
    }

    /**
     * Called when a member chunk stops qualifying — unclaimed, converted, or
     * transferred. The whole Complex reverts rather than rendering a building
     * with a hole in it.
     */
    public void onMemberLost(NodeRecord lost, World world) {
        onMemberLost(lost, anchorOf(lost), world);
    }

    /**
     * Variant that retains the anchor resolved before the lost node was
     * removed from {@link NodeStore}. This is required when the anchor itself
     * is unclaimed: after the durable delete, an id lookup can no longer find
     * it, but the remaining members still need releasing.
     */
    public void onMemberLost(NodeRecord lost, NodeRecord knownAnchor, World world) {
        if (!lost.isInComplex()) {
            return;
        }
        NodeRecord anchor = knownAnchor != null ? knownAnchor : anchorOf(lost);
        if (anchor == null) {
            lost.setComplexAnchor(0);
            return;
        }
        for (NodeRecord member : members(anchor)) {
            if (member.getId() == lost.getId()) {
                continue;
            }
            member.setComplexAnchor(0);
            nodeStore.updateAppearance(member);
            if (member.getType() == NodeType.RESIDENTIAL) {
                schematicService.restoreTerrain(member, world);
            }
        }
        lost.setComplexAnchor(0);
        if (anchor.getId() != lost.getId()) {
            rebuildAll(anchor, world);
        }
    }

    /**
     * Re-pastes every chunk of the Complex. Each member renders its own slice
     * through the ordinary per-node paste, which is why merging needs no new
     * world-writing machinery.
     *
     * <p>Members are independent jobs because they touch different chunks;
     * within one member, restore strictly precedes build.</p>
     */
    private void rebuildAll(NodeRecord anchor, World world) {
        List<NodeRecord> members = members(anchor);
        if (members.isEmpty()) {
            members = List.of(anchor);
        }
        for (NodeRecord member : members) {
            boolean isAnchor = member.getId() == anchor.getId();
            schematicService.restoreTerrain(member, world,
                    () -> schematicService.buildHousing(member, world,
                            () -> {
                                if (isAnchor) {
                                    npcManager.refreshNode(anchor, world);
                                }
                            }));
        }
    }

    /**
     * The parts a proposed Complex would draw, for previewing a merge that
     * has not happened yet.
     *
     * <p>Resolved through the same {@code pieceForCell} the finished building
     * uses, so what the player sees is exactly what they will get.</p>
     */
    public List<dev.branzx.idle.service.PreviewService.Part> planPreview(
            NodeRecord anchor, ComplexPlanner.Placement placement) {
        var registry = schematicService.getRegistry();
        List<dev.branzx.idle.service.PreviewService.Part> parts = new ArrayList<>();
        for (ChunkKey chunk : placement.chunks()) {
            boolean isAnchor = chunk.equals(anchor.getChunk());
            int col = chunk.x() - placement.min().x();
            int row = chunk.z() - placement.min().z();
            var definition = registry.pieceForCell(anchor, col, row, isAnchor);
            if (definition == null) {
                // The anchor with no Complex skin keeps its ordinary building.
                definition = registry.definitionFor(anchor);
            }
            parts.add(new dev.branzx.idle.service.PreviewService.Part(chunk, definition));
        }
        return parts;
    }

    /**
     * Turns a whole Complex to a new orientation.
     *
     * <p>Every member carries the same rotation, because each one rotates its
     * own piece about its own chunk centre; the pieces moving around each
     * other is handled by the cell mapping. A member left at the old rotation
     * would render its slice facing the wrong way.</p>
     */
    public void rotateComplex(NodeRecord anchor, int rotation, World world) {
        for (NodeRecord member : members(anchor)) {
            member.setRotation(rotation);
            nodeStore.updateAppearance(member);
        }
        rebuildAll(anchor, world);
    }

    /** Rebuilds a Complex whose appearance fields are already persisted. */
    public void rebuildComplex(NodeRecord anchor, World world) {
        rebuildAll(anchor, world);
    }

    /**
     * The parts a Complex would draw at a proposed rotation, for previewing a
     * turn before it is paid for. Resolved through the same cell mapping and
     * {@code pieceForCell} the finished building uses.
     */
    public List<dev.branzx.idle.service.PreviewService.Part> planRotation(NodeRecord anchor,
                                                                          int rotation) {
        List<NodeRecord> members = members(anchor);
        ComplexShape shape = shapeOf(anchor);
        if (members.isEmpty() || shape == null) {
            return List.of();
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (NodeRecord member : members) {
            minX = Math.min(minX, member.getChunk().x());
            minZ = Math.min(minZ, member.getChunk().z());
        }
        var registry = schematicService.getRegistry();
        List<dev.branzx.idle.service.PreviewService.Part> parts = new ArrayList<>();
        for (NodeRecord member : members) {
            int[] cell = shape.authoredCell(member.getChunk().x() - minX,
                    member.getChunk().z() - minZ, rotation);
            var definition = registry.pieceForCell(anchor, cell[0], cell[1],
                    member.getId() == anchor.getId());
            if (definition == null) {
                definition = registry.definitionFor(anchor);
            }
            parts.add(new dev.branzx.idle.service.PreviewService.Part(
                    member.getChunk(), definition));
        }
        return parts;
    }

    /** Rotations this node's Complex may take; all four when it stands alone. */
    public List<Integer> allowedRotations(NodeRecord node) {
        ComplexShape shape = shapeOf(node);
        return shape == null ? List.of(0, 1, 2, 3) : shape.allowedRotations();
    }

    /** True when any chunk belonging to this node's building is being written. */
    public boolean isBusy(NodeRecord node) {
        if (node == null || !node.isInComplex()) {
            return node != null && schematicService.isBusy(node.getChunk());
        }
        return busyAnywhere(members(node).stream().map(NodeRecord::getChunk).toList());
    }

    /** The placement a shape would take here, or null if the land will not take it. */
    public ComplexPlanner.Placement placementFor(NodeRecord anchor, ComplexShape shape) {
        return ComplexPlanner.firstFor(shape, anchor.getChunk(),
                chunk -> isFreeResidential(anchor.getOwnerUuid(), chunk));
    }

    // ---- layout for blueprint resolution ----

    /**
     * Lets the schematic registry map a member chunk onto its cell, so each
     * chunk renders the right slice of the Complex building.
     */
    public dev.branzx.idle.schematic.SchematicRegistry.ComplexLayout layout() {
        return new dev.branzx.idle.schematic.SchematicRegistry.ComplexLayout() {
            @Override
            public NodeRecord anchorOf(NodeRecord node) {
                return ComplexService.this.anchorOf(node);
            }

            @Override
            public int[] cellOf(NodeRecord node) {
                List<NodeRecord> members = members(node);
                if (members.isEmpty()) {
                    return null;
                }
                int minX = Integer.MAX_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxZ = Integer.MIN_VALUE;
                for (NodeRecord member : members) {
                    minX = Math.min(minX, member.getChunk().x());
                    minZ = Math.min(minZ, member.getChunk().z());
                    maxX = Math.max(maxX, member.getChunk().x());
                    maxZ = Math.max(maxZ, member.getChunk().z());
                }
                int col = node.getChunk().x() - minX;
                int row = node.getChunk().z() - minZ;
                NodeRecord anchor = anchorOf(node);
                int rotation = anchor == null ? 0 : anchor.getRotation();
                // The pieces move around each other as the Complex turns, so
                // this chunk renders whichever piece the rotation brings here.
                return new ComplexShape(maxX - minX + 1, maxZ - minZ + 1)
                        .authoredCell(col, row, rotation);
            }

            @Override
            public String shapeIdOf(NodeRecord node) {
                ComplexShape shape = ComplexService.this.shapeOf(node);
                return shape == null ? null : shape.id();
            }
        };
    }

    private boolean busyAnywhere(List<ChunkKey> chunks) {
        return chunks.stream().anyMatch(schematicService::isBusy);
    }

    private void audit(UUID actor, String action, String detail) {
        if (auditService != null) {
            auditService.log(actor, action, detail);
        }
    }
}
