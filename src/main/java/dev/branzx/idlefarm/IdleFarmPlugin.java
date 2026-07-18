package dev.branzx.idlefarm;

import dev.branzx.idlefarm.command.IdleCommand;
import dev.branzx.idlefarm.listener.PlayerConnectionListener;
import dev.branzx.idlefarm.listener.ProtectionListener;
import dev.branzx.idlefarm.service.ClaimService;
import dev.branzx.idlefarm.service.SchematicService;
import dev.branzx.idlefarm.service.TrustService;
import dev.branzx.idlefarm.service.WorkerNpcManager;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerDataStore;
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
    private PayoutTask payoutTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.database = new Database(this);
        this.database.init();

        this.dataStore = new PlayerDataStore(this, database);
        this.nodeStore = new NodeStore(this, database);
        this.nodeStore.loadAllSync();

        this.schematicService = new SchematicService(this, database);
        this.schematicService.loadAllSync();
        this.npcManager = new WorkerNpcManager(this, nodeStore, schematicService);
        this.claimService = new ClaimService(this, nodeStore, dataStore, schematicService, npcManager);
        this.trustService = new TrustService(nodeStore);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this, dataStore), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(nodeStore, trustService), this);
        getServer().getPluginManager().registerEvents(npcManager, this);

        // Citizens is a hard dependency, so its API is ready by now.
        npcManager.init();

        IdleCommand idleCommand = new IdleCommand(this, dataStore, nodeStore, claimService, trustService);
        getCommand("idle").setExecutor(idleCommand);
        getCommand("idle").setTabCompleter(idleCommand);

        long intervalTicks = getConfig().getLong("payout-interval-seconds", 60) * 20L;
        this.payoutTask = new PayoutTask(this, dataStore);
        this.payoutTask.runTaskTimer(this, intervalTicks, intervalTicks);
    }

    @Override
    public void onDisable() {
        if (payoutTask != null) {
            payoutTask.cancel();
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
}
