package dev.branzx.idle;

import dev.branzx.idle.command.AdminCommands;
import dev.branzx.idle.command.IdleCommand;
import dev.branzx.idle.gui.GuiManager;
import dev.branzx.idle.gui.Lang;
import dev.branzx.idle.gui.MenuItemService;
import dev.branzx.idle.schematic.SchematicRegistry;
import dev.branzx.idle.listener.PlayerConnectionListener;
import dev.branzx.idle.listener.ProtectionListener;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.BoosterService;
import dev.branzx.idle.service.ClaimService;
import dev.branzx.idle.service.DropTableService;
import dev.branzx.idle.service.ExplorationService;
import dev.branzx.idle.service.GlobalExpeditionService;
import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.service.CreditService;
import dev.branzx.idle.service.TradeService;
import dev.branzx.idle.service.PerkService;
import dev.branzx.idle.service.StreakService;
import dev.branzx.idle.service.ProductionEngine;
import dev.branzx.idle.service.ProgressionScale;
import dev.branzx.idle.service.SchematicService;
import dev.branzx.idle.service.TrustService;
import dev.branzx.idle.service.WarehouseService;
import dev.branzx.idle.service.WorkerNpcManager;
import dev.branzx.idle.service.WorkerService;
import dev.branzx.idle.service.SkinHeadCache;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.PlayerDataStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.task.PayoutTask;
import org.bukkit.plugin.java.JavaPlugin;

public final class IdlePlugin extends JavaPlugin {

    private Database database;
    private PlayerDataStore dataStore;
    private NodeStore nodeStore;
    private dev.branzx.idle.service.WorldGate worldGate;
    private ClaimService claimService;
    private TrustService trustService;
    private SchematicService schematicService;
    private dev.branzx.idle.service.PreviewService previewService;
    private dev.branzx.idle.service.UpgradeSiteService upgradeSiteService;
    private dev.branzx.idle.skin.SkinRegistry skinRegistry;
    private dev.branzx.idle.skin.PlayerSkinStore playerSkinStore;
    private dev.branzx.idle.skin.SkinSubmissionService skinSubmissionService;
    private dev.branzx.idle.complex.ComplexService complexService;
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

    /**
     * Composition root. Services are constructed in dependency order and
     * receive collaborators through their constructors. The two deliberate
     * exceptions are documented where they happen: the ExplorationService ↔
     * GameDesignService cycle and the GuiManager ↔ AdminCommands cycle.
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateLegacyBalanceConfig();
        addMissingConfigDefaults();
        Lang.init(this);
        // Built before every other service: they all ask it whether a world is
        // in play.
        this.worldGate = new dev.branzx.idle.service.WorldGate(this);

        // ---- persistence layer ----
        this.database = new Database(this);
        this.database.init();
        AuditService auditService = new AuditService(this, database);
        this.dataStore = new PlayerDataStore(this, database);
        this.nodeStore = new NodeStore(this, database);
        this.nodeStore.loadAllSync();
        this.workerStore = new WorkerStore(this, database);
        this.workerStore.loadAllSync();
        dev.branzx.idle.service.NodeAnchorStore anchorStore =
                new dev.branzx.idle.service.NodeAnchorStore(this, database);
        anchorStore.loadAllSync();

        // ---- world and content services ----
        SchematicRegistry schematicRegistry = new SchematicRegistry(this);
        schematicRegistry.loadAll();
        this.skinRegistry = new dev.branzx.idle.skin.SkinRegistry(this);
        this.skinRegistry.loadAll();
        // Skins point at schematics, so the schematic registry learns about
        // them after both exist.
        schematicRegistry.setSkinRegistry(skinRegistry);
        this.playerSkinStore = new dev.branzx.idle.skin.PlayerSkinStore(this, database);
        this.playerSkinStore.loadAllSync();
        this.skinSubmissionService = new dev.branzx.idle.skin.SkinSubmissionService(this);
        this.schematicService = new SchematicService(this, database, schematicRegistry);
        this.schematicService.loadAllSync();
        this.previewService = new dev.branzx.idle.service.PreviewService(this, schematicService);
        this.previewService.start();
        this.npcManager = new WorkerNpcManager(this, nodeStore, schematicService, workerStore, anchorStore);
        this.trustService = new TrustService(nodeStore);
        this.warehouseService = new WarehouseService(this, database);
        this.warehouseService.loadAllSync();
        DropTableService dropTableService = new DropTableService(this);
        dropTableService.load();
        // Bulk lane has safe fallbacks, so misconfig is a warning, not a boot
        // failure like the discovery drop tables.
        for (String problem : new ProgressionScale(this).validateBulkConfig()) {
            getLogger().warning("Bulk-lane config: " + problem);
        }

        // ---- gameplay services ----
        this.explorationService = new ExplorationService(this, database, nodeStore, workerStore);
        this.explorationService.loadAllSync();

        GameDesignService gameDesignService = new GameDesignService(this, database, nodeStore,
                dataStore, auditService, warehouseService, explorationService);
        gameDesignService.loadAllSync();
        this.globalExpeditionService = new GlobalExpeditionService(this, database, workerStore,
                dataStore, gameDesignService);
        this.globalExpeditionService.loadAllSync();
        // Deliberate cycle: exploration events grant design rewards while the
        // design service grants exploration EXP. One late bind breaks it.
        this.explorationService.setGameDesignService(gameDesignService);
        this.explorationService.start();
        this.globalExpeditionService.start();

        SkinHeadCache skinHeadCache = new SkinHeadCache(this);
        this.workerService = new WorkerService(this, workerStore, dataStore, database, nodeStore,
                anchorStore, auditService, globalExpeditionService, gameDesignService, skinHeadCache);
        this.workerService.loadPitySync();
        this.workerService.loadBagBonusSync();

        BoosterService boosterService = new BoosterService(this, database, dataStore);
        boosterService.loadAllSync();
        PerkService perkService = new PerkService(this, database, dataStore);
        perkService.loadAllSync();
        StreakService streakService = new StreakService(this, database, dataStore);
        streakService.loadAllSync();
        // Credit now lives in the central BranzWallet plugin; this adapter
        // forwards to its shared, read-through wallet so a grant made elsewhere
        // (a Discord top-up, an admin on another backend) is picked up here
        // without a restart.
        CreditService creditService = new CreditService(this, resolveWalletApi());
        this.tradeService =
                new TradeService(this, database, auditService, workerService, gameDesignService);

        this.claimService = new ClaimService(this, nodeStore, dataStore, schematicService, npcManager,
                explorationService, workerService, workerStore, anchorStore, globalExpeditionService,
                gameDesignService, auditService);

        this.complexService = new dev.branzx.idle.complex.ComplexService(this, nodeStore, dataStore,
                schematicService, npcManager, auditService);
        // Complex members resolve their blueprint slice through this layout.
        schematicRegistry.setComplexLayout(complexService.layout());
        // Unclaiming or converting a member must revert its Complex first.
        this.claimService.setComplexService(complexService);

        // ---- delivery layer ----
        this.guiManager = new GuiManager(this, nodeStore, workerStore, dataStore, workerService,
                warehouseService, claimService, trustService, explorationService, npcManager,
                boosterService, perkService, streakService, gameDesignService, creditService,
                dropTableService, tradeService, globalExpeditionService, skinHeadCache);
        // Deliberate cycle: AdminCommands opens admin menus through the
        // GuiManager; its constructor registers itself back via setAdminTools.
        AdminCommands adminCommands = new AdminCommands(this, nodeStore, workerStore, schematicService,
                npcManager, dropTableService, auditService, guiManager, dataStore, explorationService,
                creditService, claimService);
        IdleCommand idleCommand = new IdleCommand(this, dataStore, nodeStore, claimService, trustService,
                workerService, workerStore, npcManager, warehouseService, guiManager, adminCommands,
                tradeService);
        getCommand("idle").setExecutor(idleCommand);
        getCommand("idle").setTabCompleter(idleCommand);

        var pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(tradeService, this);
        pluginManager.registerEvents(new dev.branzx.idle.listener.WorkerPlacementListener(
                this, nodeStore, workerStore, workerService, anchorStore, npcManager, schematicService), this);
        pluginManager.registerEvents(
                new dev.branzx.idle.listener.WorkerSafetyListener(workerService, gameDesignService), this);
        pluginManager.registerEvents(
                new PlayerConnectionListener(this, dataStore, streakService, gameDesignService,
                        creditService), this);
        pluginManager.registerEvents(new ProtectionListener(nodeStore, trustService), this);
        pluginManager.registerEvents(previewService, this);
        pluginManager.registerEvents(npcManager, this);
        pluginManager.registerEvents(guiManager, this);
        pluginManager.registerEvents(guiManager.chatPrompt(), this);
        MenuItemService menuItemService = new MenuItemService(this, guiManager);
        pluginManager.registerEvents(menuItemService, this);
        menuItemService.start();

        // Citizens is a hard dependency, so its API is ready by now.
        npcManager.init();

        registerVaultEconomy();

        // ---- scheduled work ----
        long intervalTicks = getConfig().getLong("payout-interval-seconds", 60) * 20L;
        this.payoutTask = new PayoutTask(this, dataStore, boosterService, creditService);
        this.payoutTask.runTaskTimer(this, intervalTicks, intervalTicks);

        long productionTicks = getConfig().getLong("production.tick-seconds", 60) * 20L;
        this.productionEngine = new ProductionEngine(this, nodeStore, workerStore, workerService,
                explorationService, boosterService, perkService, warehouseService, dropTableService,
                globalExpeditionService, gameDesignService);
        guiManager.setProductionEngine(productionEngine);
        // First run settles any downtime accrued while the server was off.
        this.productionEngine.runTaskTimer(this, 100L, productionTicks);

        // Complete due tier upgrades once per second (needs the chunk loaded).
        getServer().getScheduler().runTaskTimer(this, () -> claimService.tickUpgrades(), 40L, 20L);
        this.upgradeSiteService =
                new dev.branzx.idle.service.UpgradeSiteService(this, nodeStore, schematicService,
                        claimService);
        this.upgradeSiteService.start();
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

    /**
     * Writes newly-introduced config keys into an existing config.yml.
     *
     * <p>{@code saveDefaultConfig()} only writes the file when it is absent, so
     * on a server that already has one a new option is invisible: the code
     * falls back to its default and the admin never learns the option exists.
     * Only genuinely missing keys are added, so admin tuning is preserved.
     */
    /**
     * Publishes Idle Coins as the server's Vault economy so other plugins can
     * read and move them without a middleman.
     *
     * <p>Coins are charged inside the same SQL transaction as the gameplay
     * write they pay for, which is why this plugin owns the currency instead of
     * mirroring another one — see {@code IdleEconomy}. Registering at HIGHEST
     * makes it the provider even when a chat or essentials plugin ships its own
     * stub economy.
     *
     * <p>Vault is a soft dependency, so its classes may simply be absent. The
     * catch is on Throwable to cover NoClassDefFoundError from a Vault jar that
     * is present but incompatible — a broken hook must not stop the plugin
     * booting.
     */
    /**
     * Resolves the central wallet service. BranzWallet is a soft dependency, so
     * it may be absent — the adapter degrades safely and callers already guard a
     * null Credit service. Throwable covers a present-but-incompatible wallet jar
     * so a broken hook cannot stop the plugin booting.
     */
    private dev.branzx.wallet.api.WalletApi resolveWalletApi() {
        try {
            var registration = getServer().getServicesManager()
                    .getRegistration(dev.branzx.wallet.api.WalletApi.class);
            if (registration == null) {
                getLogger().warning("BranzWallet not found; Credit features are disabled "
                        + "until it is installed.");
                return null;
            }
            getLogger().info("Using BranzWallet for Credit.");
            return registration.getProvider();
        } catch (Throwable t) {
            getLogger().warning("Could not resolve the BranzWallet service: " + t);
            return null;
        }
    }

    private void registerVaultEconomy() {
        if (!getConfig().getBoolean("vault.provide", true)) {
            getLogger().info("Vault economy provider disabled by config (vault.provide).");
            return;
        }
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("Vault not installed; Coins stay internal to Idle.");
            return;
        }
        try {
            getServer().getServicesManager().register(
                    net.milkbowl.vault.economy.Economy.class,
                    new dev.branzx.idle.economy.IdleEconomy(this, dataStore),
                    this,
                    org.bukkit.plugin.ServicePriority.Highest);
            getLogger().info("Registered Idle Coins as the Vault economy provider.");
            if (database.isSqlite()) {
                getLogger().warning("Vault economy is served from SQLite. On a Velocity "
                        + "network every backend needs the same MySQL database, or each "
                        + "server will hand out its own separate balances.");
            }
        } catch (Throwable t) {
            getLogger().warning("Could not register the Vault economy provider: " + t);
        }
    }

    private void addMissingConfigDefaults() {
        boolean changed = false;
        if (!getConfig().isSet("language")) {
            getConfig().set("language", Lang.FALLBACK);
            getLogger().info("Added 'language' to config.yml (see lang/ for translations).");
            changed = true;
        }
        if (!getConfig().isSet("worlds")) {
            // Seeded from the old claim-only list so an existing restriction
            // widens to the whole plugin instead of silently resetting.
            getConfig().set("worlds", getConfig().getStringList("claims.worlds"));
            getLogger().info("Added 'worlds' to config.yml (gates the whole plugin, "
                    + "replacing 'claims.worlds').");
            changed = true;
        }
        if (changed) {
            saveConfig();
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
        if (upgradeSiteService != null) {
            upgradeSiteService.shutdown();
        }
        if (previewService != null) {
            // Restores real blocks for anyone mid-preview; a reload otherwise
            // leaves ghost blocks stuck on their client until a chunk reload.
            previewService.shutdown();
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

    public dev.branzx.idle.service.WorldGate getWorldGate() {
        return worldGate;
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }

    public NodeStore getNodeStore() {
        return nodeStore;
    }

    public dev.branzx.idle.skin.SkinRegistry getSkinRegistry() {
        return skinRegistry;
    }

    public dev.branzx.idle.skin.PlayerSkinStore getPlayerSkinStore() {
        return playerSkinStore;
    }

    public dev.branzx.idle.skin.SkinSubmissionService getSkinSubmissionService() {
        return skinSubmissionService;
    }

    public dev.branzx.idle.complex.ComplexService getComplexService() {
        return complexService;
    }

    public SchematicService getSchematicService() {
        return schematicService;
    }

    public dev.branzx.idle.service.PreviewService getPreviewService() {
        return previewService;
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
