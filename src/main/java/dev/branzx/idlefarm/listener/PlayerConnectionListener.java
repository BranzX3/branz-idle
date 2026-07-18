package dev.branzx.idlefarm.listener;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public final class PlayerConnectionListener implements Listener {

    private final IdleFarmPlugin plugin;
    private final PlayerDataStore dataStore;

    public PlayerConnectionListener(IdleFarmPlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                dataStore.loadOrCreateSync(player.getUniqueId(), player.getName());
            }
        }.runTaskAsynchronously(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        var uuid = event.getPlayer().getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                dataStore.unload(uuid);
            }
        }.runTaskAsynchronously(plugin);
    }
}
