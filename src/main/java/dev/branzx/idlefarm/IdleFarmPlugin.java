package dev.branzx.idlefarm;

import dev.branzx.idlefarm.command.AdminCommands;
import dev.branzx.idlefarm.command.IdleCommand;
import dev.branzx.idlefarm.gui.GuiManager;
import dev.branzx.idlefarm.schematic.SchematicRegistry;
import dev.branzx.idlefarm.listener.PlayerConnectionListener;
import dev.branzx.idlefarm.listener.ProtectionListener;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.BoosterService;
import dev.branzx.idlefarm.service.ClaimService;
import dev.branzx.idlefarm.service.DropTableService;
import dev.branzx.idlefarm.service.ExplorationService;
import dev.branzx.idlefarm.service.GlobalExpeditionService;
import dev.branzx.idlefarm.service.GameDesignService;
import dev.branzx.idlefarm.service.CreditService;
import dev.branzx.idlefarm.service.TradeService;
import dev.branzx.idlefarm.service.PerkService;
import dev.branzx.idlefarm.service.StreakService;
import dev.branzx.idlefarm.service.ProductionEngine;
import dev.branzx.idlefarm.service.SchematicService;
import dev.branzx.idlefarm.service.TrustService;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.service.WorkerNpcManager;
import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.task.PayoutTask;
import org.bukkit.plugin.java.JavaPlugin;

public final class IdleFarmPlugin extends JavaPlugin {

    private Database database;
    private PlayerDataStore dataStore;
    private NodeStore nodeStore;
    private ClaimService claimService;
    private TrustService trustService;
    private SchematicService schematicService;
    private WorkerNpcManager npcManager;
    private WorkerStore workerStore;
    private WorkerService workerService;
    private ProductionEngine productionEngine;
    private ExplorationService explorationService;
    private WarehouseService warehouseService;
    private GuiManager guiManager;
    private PayoutTask payoutTask;
    private TradeService tradeService;
    private GlobalExpeditionService globalExpeditionService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateLegacyBalanceConfig();

        this.database = new Database(this);
        this.database.init();
        AuditService auditService = new AuditService(this, database);

        this.dataStore = new PlayerDataStore(this, database);
        this.nodeStore = new NodeStore(this, database);
        this.nodeStore.loadAllSync();

        SchematicRegistry schematicRegistry = new SchematicRegistry(this);
        schematicRegistry.loadAll();
        this.schematicService = new SchematicService(this, database, schematicRegistry);
        this.schematicService.loadAllSync();
        this.npcManager = new WorkerNpcManager(this, nodeStore, schematicService);
        this.workerStore = new WorkerStore(this, database);
        this.workerStore.loadAllSync();
        this.npcManager.setWorkerStore(workerStore);
        this.workerService = new WorkerService(this, workerStore, dataStore, database);
        this.workerService.loadPitySync();
        this.workerService.loadBagBonusSync();

        dev.branzx.idlefarm.service.NodeAnchorStore anchorStore =
                new dev.branzx.idlefarm.service.NodeAnchorStore(this, database);
        anchorStore.loadAllSync();
        this.npcManager.setAnchorStore(anchorStore);
        this.workerService.setAnchorStore(anchorStore);
        this.claimService = new ClaimService(this, nodeStore, dataStore, schematicService, npcManager);
        this.trustService = new TrustService(nodeStore);
        this.warehouseService = new WarehouseService(this, database);
        this.warehouseService.loadAllSync();

        this.explorationService = new ExplorationService(this, database, nodeStore, workerStore);
        this.explorationService.loadAllSync();
        this.explorationService.start();
        this.claimService.setLateServices(explorationService, workerService, workerStore);
        this.claimService.setAnchorStore(anchorStore);
        getServer().getPluginManager().registerEvents(
                new dev.branzx.idlefarm.listener.WorkerPlacementListener(this, nodeStore, workerStore,
                        workerService, anchorStore, npcManager, schematicService), this);

        BoosterService boosterService = new BoosterService(this, database, dataStore);
        boosterService.loadAllSync();
        PerkService perkService = new PerkService(this, database, dataStore);
        perkService.loadAllSync();
        StreakService streakService = new StreakService(this, database, dataStore);
        streakService.loadAllSync();

        GameDesignService gameDesignService =
                new GameDesignService(this, database, nodeStore, dataStore, auditService);
        gameDesignService.loadAllSync();
        gameDesignService.setRuntimeServices(warehouseService, explorationService);
        CreditService creditService =
                new CreditService(this, database, dataStore, auditService, gameDesignService);
        creditService.loadAllSync();
        this.tradeService =
                new TradeService(this, database, auditService, workerService, gameDesignService);
        getServer().getPluginManager().registerEvents(tradeService, this);
        DropTableService dropTableService = new DropTableService(this);
        dropTableService.load();
        this.claimService.setGameDesignService(gameDesignService);
        this.workerService.setGameDesignService(gameDesignService);
        this.explorationService.setGameDesignService(gameDesignService);
        getServer().getPluginManager().registerEvents(
                new dev.branzx.idlefarm.listener.WorkerSafetyListener(workerService, gameDesignService), this);

        this.guiManager = new GuiManager(this, nodeStore, workerStore, dataStore, workerService,
                warehouseService, claimService, trustService, explorationService, npcManager);
        this.guiManager.setPhase7Services(boosterService, perkService, streakService);
        this.guiManager.setGameDesignServices(gameDesignService, creditService, dropTableService);
        this.guiManager.setTradeService(tradeService);

        this.globalExpeditionService = new GlobalExpeditionService(this, database, workerStore, dataStore);
        globalExpeditionService.loadAllSync();
        globalExpeditionService.start();
        this.guiManager.setExpeditionService(globalExpeditionService);
        this.workerService.setGlobalExpeditionService(globalExpeditionService);
        this.claimService.setGlobalExpeditionService(globalExpeditionService);

        PlayerConnectionListener connectionListener = new PlayerConnectionListener(this, dataStore);
        connectionListener.setStreakService(streakService);
        connectionListener.setGameDesignService(gameDesignService);
        getServer().getPluginManager().registerEvents(connectionListener, this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(nodeStore, trustService), this);
        getServer().getPluginManager().registerEvents(npcManager, this);
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(guiManager.chatPrompt(), this);

        // Citizens is a hard dependency, so its API is ready by now.
        npcManager.init();

        this.claimService.setAuditService(auditService);
        this.workerService.setAuditService(auditService);

        AdminCommands adminCommands = new AdminCommands(this, nodeStore, workerStore, schematicService, npcManager);
        adminCommands.setPhase8Services(dropTableService, auditService, guiManager, dataStore);
        adminCommands.setExplorationService(explorationService);
        adminCommands.setCreditService(creditService);
        adminCommands.setClaimService(claimService);
        IdleCommand idleCommand = new IdleCommand(this, dataStore, nodeStore, claimService, trustService,
                workerService, workerStore, npcManager, warehouseService, guiManager, adminCommands);
        idleCommand.setTradeService(tradeService);
        getCommand("idle").setExecutor(idleCommand);
        getCommand("idle").setTabCompleter(idleCommand);

        long intervalTicks = getConfig().getLong("payout-interval-seconds", 60) * 20L;
        this.payoutTask = new PayoutTask(this, dataStore);
        this.payoutTask.setBoosterService(boosterService);
        this.payoutTask.setCreditService(creditService);
        this.payoutTask.runTaskTimer(this, intervalTicks, intervalTicks);

        long productionTicks = getConfig().getLong("production.tick-seconds", 60) * 20L;
        this.productionEngine = new ProductionEngine(this, nodeStore, workerStore, workerService);
        this.productionEngine.setExplorationService(explorationService);
        this.productionEngine.setBoosterService(boosterService);
        this.productionEngine.setPerkServices(perkService, warehouseService);
        this.productionEngine.setDropTableService(dropTableService);
        this.productionEngine.setGlobalExpeditionService(globalExpeditionService);
        this.productionEngine.setGameDesignService(gameDesignService);
        // First run settles any downtime accrued while the server was off.
        this.productionEngine.runTaskTimer(this, 100L, productionTicks);

        // Complete due tier upgrades once per second (needs the chunk loaded).
        getServer().getScheduler().runTaskTimer(this, () -> claimService.tickUpgrades(), 40L, 20L);
    }

    /**
     * Existing installations keep their copied config.yml across upgrades.
     * Move only known legacy defaults so deliberate admin tuning is preserved.
     */
    private void migrateLegacyBalanceConfig() {
        boolean changed = false;
        if (getConfig().getInt("production.buffer-capacity-per-tier", 256) == 64) {
            getConfig().set("production.buffer-capacity-per-tier", 256);
            changed = true;
        }
        if (getConfig().isSet("exploration.exp-per-level-base")) {
            getConfig().set("exploration.exp-per-level-base", null);
            changed = true;
        }
        if (getConfig().isSet("exploration.passive-exp-per-item")) {
            getConfig().set("exploration.passive-exp-per-item", null);
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("Migrated legacy progression balance to the game-design scale.");
        }
    }

    @Override
    public void onDisable() {
        if (payoutTask != null) {
            payoutTask.cancel();
        }
        if (productionEngine != null) {
            productionEngine.cancel();
        }
        if (explorationService != null) {
            explorationService.stop();
        }
        if (globalExpeditionService != null) {
            globalExpeditionService.stop();
        }
        if (npcManager != null) {
            npcManager.shutdown();
        }
        if (tradeService != null) {
            tradeService.shutdown();
        }
        if (dataStore != null) {
            dataStore.saveAllOnlineSync();
        }
        if (database != null) {
            database.shutdown();
        }
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public NodeStore getNodeStore() {
        return nodeStore;
    }

    public ClaimService getClaimService() {
        return claimService;
    }

    public TrustService getTrustService() {
        return trustService;
    }

    public ExplorationService getExplorationService() {
        return explorationService;
    }
}
