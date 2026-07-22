package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.schematic.Rotation;
import dev.branzx.idle.schematic.SchematicDefinition;
import io.papermc.paper.math.Position;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a player what a building will look like before anything is written to
 * the world.
 *
 * <p>The preview is client-side only: ghost blocks are sent to the previewing
 * player as block-change packets, so no other player sees them, no chunk is
 * modified, and cancelling costs nothing. A preview is a display and never an
 * authorization — {@link #consume} only hands back the session, and the caller
 * must re-run its own validation before charging anything.</p>
 */
public final class PreviewService implements Listener {

    /**
     * A pending placement decision. Single-use: confirming consumes it.
     *
     * <p>{@code nodeId} is 0 for a claim that has not happened yet, and the
     * node's id when an already-placed building is being re-oriented.</p>
     */
    /**
     * What confirming a preview is supposed to do. Held explicitly so a token
     * from one flow can never be spent by another's confirm handler.
     */
    public enum Kind {
        /** A chunk that has not been claimed yet. */
        CLAIM,
        /** Re-orienting a building that already stands. */
        ROTATE,
        /** Admin inspecting a submitted build; confirming does nothing. */
        REVIEW,
        /** Forming a Complex out of a node and the plots around it. */
        MERGE
    }

    /**
     * One chunk's slice of a preview. A single-chunk preview has exactly one
     * part; a Complex has one per covered chunk.
     */
    public record Part(ChunkKey chunk, SchematicDefinition definition) {
    }

    public record Session(UUID player, String token, String world, ChunkKey chunk,
                          NodeType type, List<Part> parts, int originY,
                          int rotation, int placedRotation, int obstructions, long nodeId,
                          Kind kind, long expiresAt) {

        /** Origin of the anchor chunk. */
        public Location origin(World world) {
            return originOf(world, chunk, originY);
        }

        /**
         * Every chunk shares the anchor's ground level, so a Complex cannot
         * step up and down a slope.
         */
        public Location originOf(World world, Part part) {
            return originOf(world, part.chunk(), originY);
        }

        static Location originOf(World world, ChunkKey chunk, int originY) {
            return new Location(world, (chunk.x() << 4) + 8, originY, (chunk.z() << 4) + 8);
        }

        public boolean isExistingNode() {
            return nodeId > 0;
        }

        /** The single-chunk blueprint; the anchor's part for a Complex. */
        public SchematicDefinition definition() {
            for (Part part : parts) {
                if (part.chunk().equals(chunk)) {
                    return part.definition();
                }
            }
            return parts.isEmpty() ? null : parts.getFirst().definition();
        }
    }

    private final IdlePlugin plugin;
    private final SchematicService schematicService;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Positions currently faked per player, so clearing restores exactly them. */
    private final Map<UUID, List<Position>> sent = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private BukkitRunnable ticker;

    public PreviewService(IdlePlugin plugin, SchematicService schematicService) {
        this.plugin = plugin;
        this.schematicService = schematicService;
    }

    private int timeoutSeconds() {
        return Math.max(10, plugin.getConfig().getInt("nodes.preview.timeout-seconds", 60));
    }

    /**
     * Ghost blocks are resent periodically because a chunk reload makes the
     * client redraw the real blocks and the preview would silently vanish.
     */
    public void start() {
        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Session session : List.copyOf(sessions.values())) {
                    Player player = plugin.getServer().getPlayer(session.player());
                    if (player == null || !player.isOnline()) {
                        sessions.remove(session.player());
                        sent.remove(session.player());
                        continue;
                    }
                    if (now >= session.expiresAt()) {
                        end(player);
                        player.sendMessage(net.kyori.adventure.text.Component.text(
                                "[Idle] Placement preview expired; nothing was claimed.",
                                net.kyori.adventure.text.format.NamedTextColor.GRAY));
                        continue;
                    }
                    render(player, session);
                }
            }
        };
        long period = 20L * Math.max(1,
                plugin.getConfig().getInt("nodes.preview.refresh-seconds", 5));
        ticker.runTaskTimer(plugin, period, period);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                end(player);
            }
        }
        sessions.clear();
        sent.clear();
    }

    public Session get(Player player) {
        return sessions.get(player.getUniqueId());
    }

    /**
     * Opens a preview, replacing any previous one. The returned session's
     * token is what the confirm command must carry, so a stale chat line
     * clicked later is inert rather than a second charge.
     */
    public Session open(Player player, ChunkKey chunk, NodeType type,
                        SchematicDefinition definition, int originY) {
        end(player);
        return show(player, buildSession(player, newToken(), chunk, type,
                List.of(new Part(chunk, definition)),
                originY, 0, 0, 0, Kind.CLAIM,
                System.currentTimeMillis() + timeoutSeconds() * 1000L));
    }

    /**
     * Previews a Complex before it is formed: every covered chunk is drawn
     * with the slice it would render, at the anchor's ground level.
     */
    public Session openMerge(Player player, dev.branzx.idle.node.NodeRecord anchor,
                             List<Part> parts) {
        end(player);
        return show(player, buildSession(player, newToken(), anchor.getChunk(), anchor.getType(),
                parts, anchor.getOriginY(), anchor.getRotation(), anchor.getRotation(),
                anchor.getId(), Kind.MERGE,
                System.currentTimeMillis() + timeoutSeconds() * 1000L));
    }

    /**
     * Shows a submitted build where the reviewing admin stands, without
     * touching the world. Confirming a review session does nothing: it exists
     * so an admin can walk around a submission and judge it.
     */
    public Session openReview(Player player, SchematicDefinition definition, int originY) {
        ChunkKey here = new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);
        return openReview(player, List.of(new Part(here, definition)), here, originY);
    }

    /**
     * Shows a submitted build where the reviewing admin stands, without
     * touching the world. Confirming a review session does nothing: it exists
     * so an admin can walk around a submission and judge it.
     *
     * <p>A Complex submission is laid out around the admin's chunk exactly as
     * it would sit on a real Complex, so the reviewer sees the composition
     * and not nine unrelated pieces.</p>
     */
    public Session openReview(Player player, List<Part> parts, ChunkKey anchorChunk, int originY) {
        end(player);
        return show(player, buildSession(player, newToken(), anchorChunk, NodeType.MINING,
                parts, originY, 0, 0, 0, Kind.REVIEW,
                System.currentTimeMillis() + timeoutSeconds() * 1000L));
    }

    /**
     * Previews an already-placed building at a new orientation. The ghost is
     * drawn over the real building, so the player compares the proposed
     * orientation against what is standing there now.
     */
    public Session openRotate(Player player, dev.branzx.idle.node.NodeRecord node,
                              SchematicDefinition definition, int rotation) {
        return openRotate(player, node, List.of(new Part(node.getChunk(), definition)), rotation);
    }

    /** Rotation preview spanning several chunks, for a Complex. */
    public Session openRotate(Player player, dev.branzx.idle.node.NodeRecord node,
                              List<Part> parts, int rotation) {
        end(player);
        return show(player, buildSession(player, newToken(), node.getChunk(), node.getType(),
                parts, node.getOriginY(), rotation, node.getRotation(), node.getId(),
                Kind.ROTATE, System.currentTimeMillis() + timeoutSeconds() * 1000L));
    }

    /**
     * Turns the pending building a quarter-turn clockwise and redraws.
     *
     * <p>Rotating keeps the same token and the same deadline: it changes what
     * is being offered, not whether the offer is live, and re-issuing a token
     * would break the confirm link already sitting in the player's chat.</p>
     */
    public Session rotate(Player player, String token, int quarterTurns) {
        return rotate(player, token, quarterTurns, null);
    }

    /**
     * Rotate, recomputing the parts for the new orientation.
     *
     * <p>A Complex needs this: its pieces move around each other as it turns,
     * so keeping the old parts would show every piece turned in place and
     * none of them relocated. {@code partsFor} maps the new absolute rotation
     * to the parts; pass null when the parts do not depend on it.</p>
     */
    public Session rotate(Player player, String token, int quarterTurns,
                          java.util.function.IntFunction<List<Part>> partsFor) {
        Session current = sessions.get(player.getUniqueId());
        if (current == null || !current.token().equals(token)) {
            return null;
        }
        int target = Rotation.normalize(current.rotation() + quarterTurns);
        List<Part> parts = partsFor == null ? current.parts() : partsFor.apply(target);
        if (parts == null || parts.isEmpty()) {
            parts = current.parts();
        }
        clearGhosts(player);
        return show(player, buildSession(player, current.token(), current.chunk(),
                current.type(), parts, current.originY(), target, current.placedRotation(),
                current.nodeId(), current.kind(), current.expiresAt()));
    }

    /** Recomputes the obstruction count for this orientation, over every part. */
    private Session buildSession(Player player, String token, ChunkKey chunk, NodeType type,
                                 List<Part> parts, int originY, int rotation,
                                 int placedRotation, long nodeId, Kind kind, long expiresAt) {
        int normalized = Rotation.normalize(rotation);
        int obstructions = 0;
        for (Part part : parts) {
            Location origin = Session.originOf(player.getWorld(), part.chunk(), originY);
            obstructions += schematicService.survey(part.definition(), origin, normalized,
                            standingBuilding(part.definition(), origin, nodeId, placedRotation))
                    .obstructions().size();
        }
        return new Session(player.getUniqueId(), token, player.getWorld().getName(), chunk,
                type, parts, originY, normalized, placedRotation,
                obstructions, nodeId, kind, expiresAt);
    }

    /**
     * Positions the currently-standing building occupies, for an existing
     * node. Those blocks come down before the new orientation goes up, so
     * they are not obstructions. Empty for a fresh claim.
     */
    private java.util.Set<String> standingBuilding(SchematicDefinition definition, Location origin,
                                                   long nodeId, int placedRotation) {
        if (nodeId <= 0) {
            return java.util.Set.of();
        }
        return schematicService.occupiedPositions(definition, origin, placedRotation);
    }

    private Session show(Player player, Session session) {
        sessions.put(player.getUniqueId(), session);
        render(player, session);
        return session;
    }

    /**
     * Hands back and clears the session matching this token. A wrong, missing,
     * or already-used token returns null, which is what makes a re-clicked
     * chat line harmless.
     */
    public Session consume(Player player, String token) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.token().equals(token)) {
            return null;
        }
        end(player);
        return session;
    }

    /** Ends any session and restores the player's view of the real world. */
    public void end(Player player) {
        sessions.remove(player.getUniqueId());
        clearGhosts(player);
    }

    /** Restores real blocks without touching the session (used when redrawing). */
    private void clearGhosts(Player player) {
        List<Position> positions = sent.remove(player.getUniqueId());
        if (positions == null || positions.isEmpty() || !player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        Map<Position, BlockData> real = new HashMap<>();
        for (Position position : positions) {
            real.put(position, world.getBlockAt(position.blockX(), position.blockY(),
                    position.blockZ()).getBlockData());
        }
        player.sendMultiBlockChange(real);
    }

    private String newToken() {
        return Long.toUnsignedString(random.nextLong(), 36);
    }

    /**
     * Draws the building as ghost blocks plus a particle outline.
     *
     * <p>Structural blocks render as a translucent material rather than their
     * real block data: a preview built from real blocks is indistinguishable
     * from a finished building, and players would not know whether they had
     * already paid. Solid obstructions render red — those are the blocks the
     * placement will not clear for them.</p>
     */
    private void render(Player player, Session session) {
        World world = player.getWorld();
        if (!world.getName().equals(session.world())) {
            return;
        }
        List<Ghost> drawn = new ArrayList<>();
        for (Part part : session.parts()) {
            Location origin = session.originOf(world, part);
            // Must match the survey, or the count in chat and the red blocks
            // on screen would tell the player two different stories.
            java.util.Set<String> standingBuilding = standingBuilding(part.definition(), origin,
                    session.nodeId(), session.placedRotation());

            // Same resolver the paste uses, so the ghost is exactly the
            // building that will be placed — including which way a rotated
            // stair faces.
            for (SchematicService.PasteBlock block
                    : schematicService.resolveBlocks(part.definition(), origin, session.rotation())) {
                if (block.data().getMaterial().isAir()) {
                    continue; // air carries no shape information
                }
                // A solid block already standing where the building goes is
                // the thing the player has to decide about, so it wins the
                // colour.
                var standing = world.getBlockAt(block.x(), block.y(), block.z());
                boolean blocked = !standing.getType().isAir()
                        && !SchematicService.isVegetation(standing.getType())
                        && !standing.isLiquid()
                        && !standingBuilding.contains(
                                block.x() + "," + block.y() + "," + block.z());
                drawn.add(new Ghost(Position.block(block.x(), block.y(), block.z()),
                        block.data(), blocked));
            }
        }

        List<Position> positions = new ArrayList<>(drawn.size());
        Map<Position, BlockData> ghosts = new HashMap<>();
        if (!drawn.isEmpty()) {
            // Resolved only once there is something to draw, and once per
            // render rather than once per block.
            Material ghostMaterial = ghostMaterial();
            Material obstructionMaterial = material("nodes.preview.obstruction-material",
                    Material.RED_STAINED_GLASS);
            BlockData ghostData = ghostMaterial == null ? null : ghostMaterial.createBlockData();
            BlockData obstructionData = obstructionMaterial.createBlockData();
            for (Ghost ghost : drawn) {
                ghosts.put(ghost.position(), ghost.blocked() ? obstructionData
                        : (ghostData == null ? ghost.data() : ghostData));
                positions.add(ghost.position());
            }
            player.sendMultiBlockChange(ghosts);
        }
        sent.put(player.getUniqueId(), positions);
        outline(player, session);
    }

    private record Ghost(Position position, BlockData data, boolean blocked) {
    }

    /**
     * Particle frame around the whole footprint, so the ghost reads as a
     * preview. For a Complex this is the outline of every chunk together, not
     * one box per chunk — the player is deciding about one building.
     */
    private void outline(Player player, Session session) {
        World world = player.getWorld();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Part part : session.parts()) {
            Location origin = session.originOf(world, part);
            SchematicDefinition.Bounds bounds =
                    Rotation.rotate(part.definition().bounds(), session.rotation());
            minX = Math.min(minX, origin.getBlockX() + bounds.minX());
            maxX = Math.max(maxX, origin.getBlockX() + bounds.maxX() + 1);
            minZ = Math.min(minZ, origin.getBlockZ() + bounds.minZ());
            maxZ = Math.max(maxZ, origin.getBlockZ() + bounds.maxZ() + 1);
        }
        if (minX > maxX) {
            return; // nothing to frame
        }
        double y = session.originY() + 0.1;
        for (int x = minX; x <= maxX; x++) {
            player.spawnParticle(org.bukkit.Particle.END_ROD, x, y, minZ, 1, 0, 0, 0, 0);
            player.spawnParticle(org.bukkit.Particle.END_ROD, x, y, maxZ, 1, 0, 0, 0, 0);
        }
        for (int z = minZ; z <= maxZ; z++) {
            player.spawnParticle(org.bukkit.Particle.END_ROD, minX, y, z, 1, 0, 0, 0, 0);
            player.spawnParticle(org.bukkit.Particle.END_ROD, maxX, y, z, 1, 0, 0, 0, 0);
        }
    }

    /** Null means "show the real block data" (config value NONE). */
    private Material ghostMaterial() {
        String configured = plugin.getConfig()
                .getString("nodes.preview.ghost-material", "LIGHT_BLUE_STAINED_GLASS");
        if (configured == null || configured.equalsIgnoreCase("NONE")) {
            return null;
        }
        Material material = blockMaterial(configured);
        if (material == null) {
            plugin.getLogger().warning("Invalid nodes.preview.ghost-material '" + configured
                    + "'; using LIGHT_BLUE_STAINED_GLASS.");
            return Material.LIGHT_BLUE_STAINED_GLASS;
        }
        return material;
    }

    private Material material(String path, Material fallback) {
        String configured = plugin.getConfig().getString(path, fallback.name());
        Material material = configured == null ? null : blockMaterial(configured);
        return material != null ? material : fallback;
    }

    /**
     * Enum lookup rather than {@code Material#matchMaterial}: only vanilla
     * material names are accepted here, and the registry-backed matcher pulls
     * in server state this class otherwise never needs.
     */
    private static Material blockMaterial(String name) {
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("MINECRAFT:")) {
            normalized = normalized.substring("MINECRAFT:".length());
        }
        Material material = Material.getMaterial(normalized);
        return material != null && material.isBlock() ? material : null;
    }

    // A preview is bound to a place; leaving the world or the server ends it.

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        sent.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            // The ghosts lived in the old world; drop the state, do not try to
            // resend real blocks into a world the player has left.
            sessions.remove(event.getPlayer().getUniqueId());
            sent.remove(event.getPlayer().getUniqueId());
            event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                    "[Idle] Placement preview cancelled — you changed world.",
                    net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }
}
