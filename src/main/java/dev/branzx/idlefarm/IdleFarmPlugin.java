package dev.branzx.idlefarm;

import dev.branzx.idlefarm.command.AdminCommands;
import dev.branzx.idlefarm.command.IdleCommand;
import dev.branzx.idlefarm.gui.GuiManager;
import dev.branzx.idlefarm.schematic.SchematicRegistry;
import dev.branzx.idlefarm.listener.PlayerConnectionListener;
import dev.branzx.idlefarm.listener.ProtectionListener;
import dev.branzx.idlefarm.service.BoosterService;
import dev.branzx.idlefarm.service.ClaimService;
import dev.branzx.idlefarm.service.ExplorationService;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.database = new Database(this);
        this.database.init();

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
        this.claimService = new ClaimService(this, nodeStore, dataStore, schematicService, npcManager);
        this.trustService = new TrustService(nodeStore);
        this.warehouseService = new WarehouseService(this, database);
        this.warehouseService.loadAllSync();

        this.explorationService = new ExplorationService(this, database, nodeStore, workerStore);
        this.explorationService.loadAllSync();
        this.explorationService.start();
        this.claimService.setLateServices(explorationService, workerService, workerStore);

        BoosterService boosterService = new BoosterService(this, database, dataStore);
        boosterService.loadAllSync();
        PerkService perkService = new PerkService(this, database, dataStore);
        perkService.loadAllSync();
        StreakService streakService = new StreakService(this, database, dataStore);
        streakService.loadAllSync();

        this.guiManager = new GuiManager(this, nodeStore, workerStore, dataStore, workerService,
                warehouseService, claimService, trustService, explorationService, npcManager);
        this.guiManager.setPhase7Services(boosterService, perkService, streakService);

        PlayerConnectionListener connectionListener = new PlayerConnectionListener(this, dataStore);
        connectionListener.setStreakService(streakService);
        getServer().getPluginManager().registerEvents(connectionListener, this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(nodeStore, trustService), this);
        getServer().getPluginManager().registerEvents(npcManager, this);
        getServer().getPluginManager().registerEvents(guiManager, this);

        // Citizens is a hard dependency, so its API is ready by now.
        npcManager.init();

        AdminCommands adminCommands = new AdminCommands(this, nodeStore, workerStore, schematicService, npcManager);
        IdleCommand idleCommand = new IdleCommand(this, dataStore, nodeStore, claimService, trustService,
                workerService, workerStore, npcManager, warehouseService, guiManager, adminCommands);
        getCommand("idle").setExecutor(idleCommand);
        getCommand("idle").setTabCompleter(idleCommand);

        long intervalTicks = getConfig().getLong("payout-interval-seconds", 60) * 20L;
        this.payoutTask = new PayoutTask(this, dataStore);
        this.payoutTask.setBoosterService(boosterService);
        this.payoutTask.runTaskTimer(this, intervalTicks, intervalTicks);

        long productionTicks = getConfig().getLong("production.tick-seconds", 60) * 20L;
        this.productionEngine = new ProductionEngine(this, nodeStore, workerStore, workerService);
        this.productionEngine.setExplorationService(explorationService);
        this.productionEngine.setBoosterService(boosterService);
        this.productionEngine.setPerkServices(perkService, warehouseService);
        // First run settles any downtime accrued while the server was off.
        this.productionEngine.runTaskTimer(this, 100L, productionTicks);
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
        if (npcManager != null) {
            npcManager.shutdown();
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
