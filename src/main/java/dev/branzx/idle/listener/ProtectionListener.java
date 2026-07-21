package dev.branzx.idle.listener;

import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.service.TrustService;
import dev.branzx.idle.storage.NodeStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public final class ProtectionListener implements Listener {

    private final NodeStore nodeStore;
    private final TrustService trustService;

    public ProtectionListener(NodeStore nodeStore, TrustService trustService) {
        this.nodeStore = nodeStore;
        this.trustService = trustService;
    }

    private NodeRecord nodeAt(Location location) {
        return nodeStore.getByChunk(new ChunkKey(
                location.getWorld().getName(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4));
    }

    private boolean denied(Player player, Location location) {
        NodeRecord node = nodeAt(location);
        if (node == null) {
            return false;
        }
        if (trustService.canBuild(player.getUniqueId(), node)) {
            return false;
        }
        player.sendActionBar(Component.text("This area is protected.", NamedTextColor.RED));
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (denied(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (denied(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Covers containers, doors, buttons, levers, etc. Fine-grained
        // allow-lists (e.g. doors for visitors) can come later via config.
        if (denied(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (denied(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (denied(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> nodeAt(block.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> nodeAt(block.getLocation()) != null);
    }
}
