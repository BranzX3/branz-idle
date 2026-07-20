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
    private final ChatPrompt chatPrompt;
    private final dev.branzx.idlefarm.service.BoosterService boosterService;
    private final dev.branzx.idlefarm.service.PerkService perkService;
    private final dev.branzx.idlefarm.service.StreakService streakService;
    private final dev.branzx.idlefarm.service.GameDesignService gameDesignService;
    private final dev.branzx.idlefarm.service.CreditService creditService;
    private final dev.branzx.idlefarm.service.DropTableService dropTableService;
    private final dev.branzx.idlefarm.service.TradeService tradeService;
    private final dev.branzx.idlefarm.service.GlobalExpeditionService expeditionService;
    // Deliberate late binding: AdminCommands needs this GuiManager to open
    // admin menus, so the pair cannot both be constructor-injected.
    private dev.branzx.idlefarm.command.AdminCommands adminCommands;
    private dev.branzx.idlefarm.service.AuditService auditService;

    public GuiManager(IdleFarmPlugin plugin, NodeStore nodeStore, WorkerStore workerStore,
                      PlayerDataStore dataStore, WorkerService workerService,
                      WarehouseService warehouseService, ClaimService claimService,
                      TrustService trustService, ExplorationService explorationService,
                      WorkerNpcManager npcManager,
                      dev.branzx.idlefarm.service.BoosterService boosterService,
                      dev.branzx.idlefarm.service.PerkService perkService,
                      dev.branzx.idlefarm.service.StreakService streakService,
                      dev.branzx.idlefarm.service.GameDesignService gameDesignService,
                      dev.branzx.idlefarm.service.CreditService creditService,
                      dev.branzx.idlefarm.service.DropTableService dropTableService,
                      dev.branzx.idlefarm.service.TradeService tradeService,
                      dev.branzx.idlefarm.service.GlobalExpeditionService expeditionService) {
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
        this.chatPrompt = new ChatPrompt(plugin);
        this.boosterService = boosterService;
        this.perkService = perkService;
        this.streakService = streakService;
        this.gameDesignService = gameDesignService;
        this.creditService = creditService;
        this.dropTableService = dropTableService;
        this.tradeService = tradeService;
        this.expeditionService = expeditionService;
    }

    public dev.branzx.idlefarm.service.GameDesignService gameDesignService() {
        return gameDesignService;
    }

    public dev.branzx.idlefarm.service.CreditService creditService() {
        return creditService;
    }

    public dev.branzx.idlefarm.service.DropTableService dropTableService() {
        return dropTableService;
    }

    public dev.branzx.idlefarm.service.TradeService tradeService() {
        return tradeService;
    }

    public void setAdminTools(dev.branzx.idlefarm.command.AdminCommands adminCommands,
                              dev.branzx.idlefarm.service.AuditService auditService) {
        this.adminCommands = adminCommands;
        this.auditService = auditService;
    }

    public dev.branzx.idlefarm.service.AuditService auditService() {
        return auditService;
    }

    /** Execute an existing audited admin action without making the admin type a command. */
    public void runAdmin(Player player, String... arguments) {
        if (adminCommands == null) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Admin tools are not ready.", net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        String[] commandArgs = new String[arguments.length + 1];
        commandArgs[0] = "admin";
        System.arraycopy(arguments, 0, commandArgs, 1, arguments.length);
        adminCommands.handle(player, commandArgs);
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

    public ChatPrompt chatPrompt() {
        return chatPrompt;
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

    public void openAdminHub(Player player) {
        if (!hasAdminAccess(player)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "คุณไม่มีสิทธิ์เปิด Admin Hub",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        new AdminHubMenu(player, this).open();
    }

    public boolean hasAdminAccess(Player player) {
        return player.hasPermission("idlefarm.admin")
                || player.hasPermission("idlefarm.admin.operations")
                || player.hasPermission("idlefarm.admin.content")
                || player.hasPermission("idlefarm.admin.economy")
                || player.hasPermission("idlefarm.admin.audit");
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

    public void openWorkerBag(Player player) {
        new WorkerBagMenu(player, this, 0).open();
    }

    public void openWorkerDetail(Player player, java.util.UUID workerUuid) {
        new WorkerDetailMenu(player, this, workerUuid).open();
    }

    public void openLeaderboard(Player player) {
        new LeaderboardMenu(player, this).open();
    }

    public void openTrust(Player player) {
        new TrustMenu(player, this).open();
    }

    public void openTerritoryMap(Player player) {
        new TerritoryMapMenu(player, this).open();
    }

    public void openShop(Player player) {
        new ShopMenu(player, this).open();
    }

    public dev.branzx.idlefarm.service.GlobalExpeditionService expeditionService() {
        return expeditionService;
    }

    public void openExpedition(Player player) {
        new ExpeditionMenu(player, this, expeditionService).open();
    }

    public void openProgress(Player player) {
        new ProgressMenu(player, this).open();
    }

    public void openTrade(Player player) {
        new TradeMenu(player, this).open();
    }

    // ---- listener ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        // All menus are click-driven; item movement is never allowed. This
        // is the robust pattern (no placeholder-swap or item-loss bugs).
        boolean topClick = event.getRawSlot() < menu.getInventory().getSize();
        if (topClick) {
            menu.click(event);
        } else {
            menu.clickPlayerInventory(event);
        }
        // Cancel everything: top-slot handlers already did their work, and a
        // shift-click from the player inventory must not dump items into the menu.
        event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < menu.getInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
