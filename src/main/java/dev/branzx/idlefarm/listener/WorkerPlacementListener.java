package dev.branzx.idlefarm.listener;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.schematic.SchematicDefinition;
import dev.branzx.idlefarm.service.NodeAnchorStore;
import dev.branzx.idlefarm.service.SchematicService;
import dev.branzx.idlefarm.service.WorkerNpcManager;
import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage B — placing a worker contract head IS the marker. Placing one inside
 * your own production node (free slot) assigns the worker there, sets its
 * spawn anchor to the placed block, then enters "set-work mode": click a
 * block in the node for the work point, or type auto / cancel in chat.
 */
public final class WorkerPlacementListener implements Listener {

    private record Pending(UUID workerUuid, long nodeId, Location spawn) {
    }

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final WorkerService workerService;
    private final NodeAnchorStore anchorStore;
    private final WorkerNpcManager npcManager;
    private final SchematicService schematicService;
    private final Map<UUID, Pending> setWork = new ConcurrentHashMap<>();

    public WorkerPlacementListener(IdleFarmPlugin plugin, NodeStore nodeStore, WorkerStore workerStore,
                                   WorkerService workerService, NodeAnchorStore anchorStore,
                                   WorkerNpcManager npcManager, SchematicService schematicService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.workerService = workerService;
        this.anchorStore = anchorStore;
        this.npcManager = npcManager;
        this.schematicService = schematicService;
    }

    private NodeRecord nodeAt(Location loc) {
        return nodeStore.getByChunk(new ChunkKey(loc.getWorld().getName(),
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        WorkerRecord worker = workerService.fromItem(event.getItemInHand());
        if (worker == null) {
            return; // not a worker contract
        }
        // Worker heads are never placeable as decoration.
        event.setCancelled(true);

        if (!WorkerRecord.STATE_ITEM.equals(worker.getState())) {
            player.sendMessage(Component.text("That worker isn't available to place.", NamedTextColor.RED));
            return;
        }
        Block block = event.getBlockPlaced();
        NodeRecord node = nodeAt(block.getLocation());
        if (node == null || !node.getType().isProduction()
                || !node.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Place worker heads inside your own production node.",
                    NamedTextColor.RED));
            return;
        }
        int used = workerStore.getAssigned(node.getId()).size();
        if (used >= node.getTier()) {
            player.sendMessage(Component.text("No free worker slot (tier " + node.getTier() + ").",
                    NamedTextColor.RED));
            return;
        }

        WorkerService.Result result = workerService.assign(player.getUniqueId(), worker, node);
        if (!result.success()) {
            player.sendMessage(Component.text(result.message(), NamedTextColor.RED));
            return;
        }
        consumeOne(player, event.getHand());

        Location spawn = block.getLocation();
        // Seed the override with spawn = placement, work = spawn (until set).
        anchorStore.set(worker.getWorkerUuid(), node.getId(), toAnchor(spawn, spawn));
        npcManager.refreshNode(node, player.getWorld());
        setWork.put(player.getUniqueId(), new Pending(worker.getWorkerUuid(), node.getId(), spawn));

        player.sendMessage(Component.text("✔ " + worker.getName() + " placed here (spawn set).",
                NamedTextColor.GREEN));
        player.sendMessage(Component.text("» Now CLICK a block in this node for its work point, "
                + "or type 'auto' (preset) / 'cancel'.", NamedTextColor.YELLOW));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Pending pending = setWork.get(event.getPlayer().getUniqueId());
        if (pending == null || event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        NodeRecord node = nodeAt(block.getLocation());
        if (node == null || node.getId() != pending.nodeId()) {
            player.sendMessage(Component.text("The work point must be inside the same node.",
                    NamedTextColor.RED));
            return;
        }
        finishWork(player, pending, block.getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Pending pending = setWork.get(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!msg.equalsIgnoreCase("auto") && !msg.equalsIgnoreCase("cancel")) {
            return; // let normal chat through; only intercept the keywords
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            setWork.remove(player.getUniqueId());
            if (msg.equalsIgnoreCase("cancel")) {
                cancelPlacement(player, pending);
            } else {
                autoWork(player, pending);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Leaving mid-setup keeps spawn=work (worker just stays home).
        setWork.remove(event.getPlayer().getUniqueId());
    }

    // ---- helpers ----

    private void finishWork(Player player, Pending pending, Location work) {
        setWork.remove(player.getUniqueId());
        anchorStore.set(pending.workerUuid(), pending.nodeId(), toAnchor(pending.spawn(), work));
        refresh(pending.nodeId(), player);
        player.sendMessage(Component.text("✔ Work point set — spawn/work configured.",
                NamedTextColor.GREEN));
    }

    private void autoWork(Player player, Pending pending) {
        NodeRecord node = nodeById(pending.nodeId());
        if (node == null) {
            return;
        }
        SchematicDefinition def = schematicService.getRegistry().forNodeType(node.getType(), node.getTier());
        int slot = slotOf(node, pending.workerUuid());
        Location presetWork = schematicService.resolve(node, player.getWorld(),
                def.workAnchorOrFallback(Math.max(0, slot)));
        anchorStore.set(pending.workerUuid(), pending.nodeId(), toAnchor(pending.spawn(), presetWork));
        refresh(pending.nodeId(), player);
        player.sendMessage(Component.text("✔ Work point set to preset default.", NamedTextColor.GREEN));
    }

    private void cancelPlacement(Player player, Pending pending) {
        WorkerRecord worker = workerStore.get(pending.workerUuid());
        if (worker != null && worker.getAssignedNodeId() != null) {
            var result = workerService.eject(player.getUniqueId(), worker);
            if (result.item() != null) {
                var leftover = player.getInventory().addItem(result.item());
                for (var overflow : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow);
                }
            }
        }
        anchorStore.clearWorker(pending.workerUuid());
        refresh(pending.nodeId(), player);
        player.sendMessage(Component.text("Placement cancelled — worker returned.", NamedTextColor.YELLOW));
    }

    private void refresh(long nodeId, Player player) {
        NodeRecord node = nodeById(nodeId);
        if (node != null) {
            npcManager.refreshNode(node, player.getWorld());
        }
    }

    private NodeRecord nodeById(long nodeId) {
        return nodeStore.getAll().stream().filter(n -> n.getId() == nodeId).findFirst().orElse(null);
    }

    private int slotOf(NodeRecord node, UUID workerUuid) {
        var assigned = workerStore.getAssigned(node.getId());
        for (int i = 0; i < assigned.size(); i++) {
            if (assigned.get(i).getWorkerUuid().equals(workerUuid)) {
                return i;
            }
        }
        return 0;
    }

    private NodeAnchorStore.SlotAnchor toAnchor(Location spawn, Location work) {
        return new NodeAnchorStore.SlotAnchor(
                spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ(),
                work.getBlockX(), work.getBlockY(), work.getBlockZ());
    }

    private void consumeOne(Player player, org.bukkit.inventory.EquipmentSlot hand) {
        var inv = player.getInventory();
        var item = hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? inv.getItemInOffHand() : inv.getItemInMainHand();
        item.setAmount(item.getAmount() - 1);
    }
}
