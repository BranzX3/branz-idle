package dev.branzx.idlefarm;

import dev.branzx.idlefarm.command.IdleCommand;
import dev.branzx.idlefarm.listener.PlayerConnectionListener;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.task.PayoutTask;
import org.bukkit.plugin.java.JavaPlugin;

public final class IdleFarmPlugin extends JavaPlugin {

    private PlayerDataStore dataStore;
    private PayoutTask payoutTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.dataStore = new PlayerDataStore(this);
        this.dataStore.init();

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this, dataStore), this);

        IdleCommand idleCommand = new IdleCommand(this, dataStore);
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
        if (dataStore != null) {
            dataStore.saveAllOnlineSync();
            dataStore.shutdown();
        }
    }

    public PlayerDataStore getDataStore() {
        return dataStore;
    }
}
