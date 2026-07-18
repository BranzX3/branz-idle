package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.ClaimService;
import dev.branzx.idlefarm.service.ExplorationService;
import dev.branzx.idlefarm.service.TrustService;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.service.WorkerNpcManager;
import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;

/**
 * Central access point for all menus plus the click/drag listener. Menus are
 * identified by their InventoryHolder being a {@link Menu}; clicks route to
 * the menu's own handlers and item movement is blocked unless the menu opts
 * in (e.g. warehouse withdraw slots handle their own logic).
 */
public final class GuiManager implements Listener {

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final PlayerDataStore dataStore;
    private final WorkerService workerService;
    private final WarehouseService warehouseService;
    private final ClaimService claimService;
    private final TrustService trustService;
    private final ExplorationService explorationService;
    private final WorkerNpcManager npcManager;

    public GuiManager(IdleFarmPlugin plugin, NodeStore nodeStore, WorkerStore workerStore,
                      PlayerDataStore dataStore, WorkerService workerService,
                      WarehouseService warehouseService, ClaimService claimService,
                      TrustService trustService, ExplorationService explorationService,
                      WorkerNpcManager npcManager) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.dataStore = dataStore;
        this.workerService = workerService;
        this.warehouseService = warehouseService;
        this.claimService = claimService;
        this.trustService = trustService;
        this.explorationService = explorationService;
        this.npcManager = npcManager;
    }

    // ---- accessors for menus ----

    public IdleFarmPlugin plugin() {
        return plugin;
    }

    public NodeStore nodeStore() {
        return nodeStore;
    }

    public WorkerStore workerStore() {
        return workerStore;
    }

    public PlayerDataStore dataStore() {
        return dataStore;
    }

    public WorkerService workerService() {
        return workerService;
    }

    public WarehouseService warehouseService() {
        return warehouseService;
    }

    public ClaimService claimService() {
        return claimService;
    }

    public TrustService trustService() {
        return trustService;
    }

    public ExplorationService explorationService() {
        return explorationService;
    }

    public WorkerNpcManager npcManager() {
        return npcManager;
    }

    // ---- openers ----

    public void openMainHub(Player player) {
        new MainHubMenu(player, this).open();
    }

    public void openNodes(Player player) {
        new NodesMenu(player, this, 0).open();
    }

    public void openNodeDetail(Player player, NodeRecord node) {
        new NodeDetailMenu(player, this, node.getId()).open();
    }

    public void openWarehouse(Player player, UUID owner) {
        new WarehouseMenu(player, this, owner, 0).open();
    }

    public void openWorkers(Player player) {
        new WorkersMenu(player, this).open();
    }

    // ---- listener ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        // Click in the top (menu) inventory.
        if (event.getRawSlot() < menu.getInventory().getSize()) {
            boolean handled = menu.click(event);
            if (!menu.allowItemMovement()) {
                event.setCancelled(true);
            } else if (!handled) {
                event.setCancelled(true);
            }
        } else if (!menu.allowItemMovement()) {
            // Clicking own inventory while a locked menu is open: block shift-move in.
            if (event.isShiftClick()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && !menu.allowItemMovement()) {
            event.setCancelled(true);
        }
    }
}
