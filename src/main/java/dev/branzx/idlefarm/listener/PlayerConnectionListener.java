package dev.branzx.idlefarm.listener;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.service.StreakService;
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
    private StreakService streakService;
    private dev.branzx.idlefarm.service.GameDesignService gameDesignService;

    public PlayerConnectionListener(IdleFarmPlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    public void setStreakService(StreakService streakService) {
        this.streakService = streakService;
    }

    public void setGameDesignService(dev.branzx.idlefarm.service.GameDesignService gameDesignService) {
        this.gameDesignService = gameDesignService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                dataStore.loadOrCreateSync(player.getUniqueId(), player.getName());
                if (!player.isOnline()) {
                    // A fast disconnect can race the asynchronous load. Do
                    // not leave a ghost "online" cache entry behind.
                    dataStore.unload(player.getUniqueId());
                    return;
                }
                if (gameDesignService != null) {
                    gameDesignService.onLogin(player.getUniqueId());
                }
                // Streak bonus needs the balance loaded; hop back to main thread.
                if (streakService != null) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (player.isOnline()) {
                                streakService.handleLogin(player);
                            }
                        }
                    }.runTask(plugin);
                }
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
