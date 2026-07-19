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
    private dev.branzx.idlefarm.service.BoosterService boosterService;
    private dev.branzx.idlefarm.service.PerkService perkService;
    private dev.branzx.idlefarm.service.StreakService streakService;

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

    public void setPhase7Services(dev.branzx.idlefarm.service.BoosterService boosterService,
                                  dev.branzx.idlefarm.service.PerkService perkService,
                                  dev.branzx.idlefarm.service.StreakService streakService) {
        this.boosterService = boosterService;
        this.perkService = perkService;
        this.streakService = streakService;
    }

    // ---- accessors for menus ----

    public dev.branzx.idlefarm.service.BoosterService boosterService() {
        return boosterService;
    }

    public dev.branzx.idlefarm.service.PerkService perkService() {
        return perkService;
    }

    public dev.branzx.idlefarm.service.StreakService streakService() {
        return streakService;
    }

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

    public void openFuse(Player player) {
        new FuseMenu(player, this).open();
    }

    public void openTerritoryMap(Player player) {
        new TerritoryMapMenu(player, this).open();
    }

    public void openShop(Player player) {
        new ShopMenu(player, this).open();
    }

    private dev.branzx.idlefarm.service.GlobalExpeditionService expeditionService;

    public void setExpeditionService(dev.branzx.idlefarm.service.GlobalExpeditionService expeditionService) {
        this.expeditionService = expeditionService;
    }

    public dev.branzx.idlefarm.service.GlobalExpeditionService expeditionService() {
        return expeditionService;
    }

    public void openExpedition(Player player) {
        new ExpeditionMenu(player, this, expeditionService).open();
    }

    // ---- listener ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        boolean topClick = event.getRawSlot() < menu.getInventory().getSize();
        if (topClick) {
            // Free input slots (e.g. fuse inputs): allow the placement, then
            // let the menu react on the next tick to recompute its display.
            if (menu.isInputSlot(event.getRawSlot())) {
                menu.onInputChanged();
                return; // not cancelled — the item moves normally
            }
            boolean handled = menu.click(event);
            event.setCancelled(true);
            if (handled) {
                return;
            }
        } else {
            // Shift-clicking from the player inventory could dump items into a
            // locked menu; only allow it when the menu has input slots to catch it.
            if (event.isShiftClick() && !menu.hasInputSlots()) {
                event.setCancelled(true);
            } else if (event.isShiftClick()) {
                menu.onInputChanged();
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        // Cancel drags that touch any non-input slot in the menu area.
        for (int raw : event.getRawSlots()) {
            if (raw < menu.getInventory().getSize() && !menu.isInputSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        menu.onInputChanged();
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.onClose();
        }
    }
}
