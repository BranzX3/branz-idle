package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.schematic.Rotation;
import dev.branzx.idle.schematic.SchematicDefinition;
import dev.branzx.idle.storage.NodeStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a tier upgrade visible while it is running.
 *
 * <p>An upgrade takes minutes, during which the building looks untouched and
 * only a chat line said anything happened. This adds a progress display over
 * the site plus periodic construction particles and sound. The completion
 * animation — the new building rising a layer at a time — belongs to
 * {@link SchematicService}.</p>
 *
 * <p>Nothing here is persisted and no world block is touched: the display is
 * a non-persistent entity rebuilt from {@code upgrade_ends_at}, so a restart
 * or a crash cannot leave anything behind to clean up.</p>
 */
public final class UpgradeSiteService {

    private final IdlePlugin plugin;
    private final NodeStore nodeStore;
    private final SchematicService schematicService;
    private final ClaimService claimService;
    /** nodeId -> the display entity showing its progress. */
    private final Map<Long, UUID> displays = new ConcurrentHashMap<>();
    private BukkitRunnable ticker;
    private int tick;

    public UpgradeSiteService(IdlePlugin plugin, NodeStore nodeStore,
                              SchematicService schematicService, ClaimService claimService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.schematicService = schematicService;
        this.claimService = claimService;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("nodes.upgrade-site.enabled", true)) {
            return;
        }
        ticker = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        ticker.runTaskTimer(plugin, 20L, 20L);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Long nodeId : Set.copyOf(displays.keySet())) {
            clear(nodeId);
        }
    }

    /** Removes the progress display for a node, if it has one. */
    public void clear(long nodeId) {
        UUID entityId = displays.remove(nodeId);
        if (entityId == null) {
            return;
        }
        Entity entity = plugin.getServer().getEntity(entityId);
        if (entity != null) {
            entity.remove();
        }
    }

    private void tick() {
        tick++;
        Set<Long> upgrading = new HashSet<>();
        long now = System.currentTimeMillis();
        for (NodeRecord node : nodeStore.getAll()) {
            if (!node.isUpgrading() || !node.getType().isProduction()) {
                continue;
            }
            World world = plugin.getServer().getWorld(node.getChunk().world());
            // An unloaded chunk has nobody to show it to, and spawning an
            // entity there would force-load it.
            if (world == null || !world.isChunkLoaded(node.getChunk().x(), node.getChunk().z())) {
                clear(node.getId());
                continue;
            }
            upgrading.add(node.getId());
            render(node, world, now);
        }
        // Completed, cancelled, or unclaimed while we were not looking.
        for (Long nodeId : Set.copyOf(displays.keySet())) {
            if (!upgrading.contains(nodeId)) {
                clear(nodeId);
            }
        }
    }

    private void render(NodeRecord node, World world, long now) {
        Location anchor = displayAnchor(node, world);
        TextDisplay display = display(node, anchor);
        if (display == null) {
            return;
        }
        display.text(progressText(node, now));

        int particleTicks = Math.max(1,
                plugin.getConfig().getInt("nodes.upgrade-site.effect-seconds", 3));
        if (tick % particleTicks == 0) {
            effects(node, world, anchor);
        }
    }

    /**
     * Progress from the upgrade's own duration rather than a stored start
     * time: the end timestamp plus the build time are enough, and that keeps
     * the display correct across a restart. The duration comes from
     * {@link ClaimService#buildSeconds(int)} — the same value that set the
     * deadline — so the bar cannot drift from the real schedule.
     */
    private Component progressText(NodeRecord node, long now) {
        int targetTier = node.getTier() + 1;
        long totalMillis = claimService.buildSeconds(targetTier) * 1000L;
        long remaining = Math.max(0L, node.getUpgradeEndsAt() - now);
        int percent = percentComplete(remaining, totalMillis);
        long seconds = (remaining + 999) / 1000;
        String clock = String.format("%d:%02d", seconds / 60, seconds % 60);
        return Component.text("⛏ Upgrading to Tier " + targetTier, NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text(bar(percent) + " " + percent + "%  " + clock,
                        NamedTextColor.YELLOW));
    }

    /**
     * Percentage elapsed, clamped to 0-100. A non-positive total means the
     * duration was misconfigured; report complete rather than divide by zero
     * or show a nonsense figure.
     */
    static int percentComplete(long remainingMillis, long totalMillis) {
        if (totalMillis <= 0) {
            return 100;
        }
        long elapsed = totalMillis - Math.max(0L, remainingMillis);
        return (int) Math.min(100, Math.max(0, Math.round(elapsed * 100.0 / totalMillis)));
    }

    static String bar(int percent) {
        int filled = Math.round(percent / 10f);
        return "▬".repeat(filled) + "·".repeat(Math.max(0, 10 - filled));
    }

    /** Just above the roof of the building being replaced. */
    private Location displayAnchor(NodeRecord node, World world) {
        SchematicDefinition definition = schematicService.getRegistry().definitionFor(node);
        SchematicDefinition.Bounds bounds =
                Rotation.rotate(definition.bounds(), node.getRotation());
        Location origin = schematicService.origin(node, world);
        return origin.add(0.5, bounds.maxY() + 1.5, 0.5);
    }

    /** Reuses the existing display, or spawns one if it is gone. */
    private TextDisplay display(NodeRecord node, Location anchor) {
        UUID existing = displays.get(node.getId());
        if (existing != null) {
            Entity entity = plugin.getServer().getEntity(existing);
            if (entity instanceof TextDisplay display && display.isValid()) {
                if (display.getLocation().distanceSquared(anchor) > 0.01) {
                    display.teleport(anchor);
                }
                return display;
            }
            displays.remove(node.getId());
        }
        TextDisplay display = anchor.getWorld().spawn(anchor, TextDisplay.class, spawned -> {
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setSeeThrough(false);
            spawned.setViewRange(0.6f);
            // Never written to disk: the display is derived state, and an
            // orphan hologram is worse than a missing one.
            spawned.setPersistent(false);
        });
        displays.put(node.getId(), display.getUniqueId());
        return display;
    }

    private void effects(NodeRecord node, World world, Location anchor) {
        SchematicDefinition definition = schematicService.getRegistry().definitionFor(node);
        SchematicDefinition.Bounds bounds =
                Rotation.rotate(definition.bounds(), node.getRotation());
        Location origin = schematicService.origin(node, world);
        double spreadX = Math.max(0.5, bounds.width() / 2.0);
        double spreadZ = Math.max(0.5, bounds.depth() / 2.0);
        Location center = origin.clone().add(0.5, bounds.height() / 2.0, 0.5);
        world.spawnParticle(org.bukkit.Particle.CLOUD, center, 6,
                spreadX, bounds.height() / 2.0, spreadZ, 0.0);
        world.spawnParticle(org.bukkit.Particle.CRIT, center, 8,
                spreadX, bounds.height() / 2.0, spreadZ, 0.0);
        // Quiet and at the site only; an upgrade runs for minutes and this
        // must not become the sound the owner remembers the game by.
        world.playSound(anchor, org.bukkit.Sound.BLOCK_SCAFFOLDING_PLACE, 0.35f, 0.9f);
    }
}
