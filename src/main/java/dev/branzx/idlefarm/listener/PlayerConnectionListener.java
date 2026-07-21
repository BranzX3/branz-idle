package dev.branzx.idlefarm.listener;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.command.CommandLinks;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.StreakService;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public final class PlayerConnectionListener implements Listener {

    private final IdleFarmPlugin plugin;
    private final PlayerDataStore dataStore;
    private final StreakService streakService;
    private final dev.branzx.idlefarm.service.GameDesignService gameDesignService;

    public PlayerConnectionListener(IdleFarmPlugin plugin, PlayerDataStore dataStore,
                                    StreakService streakService,
                                    dev.branzx.idlefarm.service.GameDesignService gameDesignService) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.streakService = streakService;
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
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (streakService != null) {
                            streakService.handleLogin(player);
                        }
                        sendJoinSummary(player);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * One consolidated "while you were away" block with click actions —
     * never more than three lines, and nothing when all systems are idle.
     */
    private void sendJoinSummary(Player player) {
        var nodeStore = plugin.getNodeStore();
        var exploration = plugin.getExplorationService();
        if (nodeStore == null) {
            return;
        }
        List<NodeRecord> production = nodeStore.getByOwner(player.getUniqueId()).stream()
                .filter(node -> node.getType().isProduction()).toList();
        if (production.isEmpty()) {
            return;
        }
        List<NodeRecord> fullNodes = production.stream()
                .filter(node -> NodeRecord.STATE_STORAGE_FULL.equals(node.getState())).toList();
        long pendingCollect = production.stream()
                .mapToLong(node -> node.storageTotal() + node.bulkStorageTotal()).sum();
        NodeRecord claimable = null;
        NodeRecord waiting = null;
        if (exploration != null) {
            for (NodeRecord node : production) {
                var event = exploration.getEvent(node.getId());
                if (event == null) {
                    continue;
                }
                if (claimable == null && "COMPLETED".equals(event.getState())) {
                    claimable = node;
                } else if (waiting == null && "AVAILABLE".equals(event.getState())) {
                    waiting = node;
                }
            }
        }
        if (fullNodes.isEmpty() && claimable == null && waiting == null && pendingCollect <= 0) {
            return;
        }
        player.sendMessage(Component.text("[IdleFarm] While you were away:",
                NamedTextColor.GOLD));
        // Full buffers already prompt collection; only surface a general
        // "supplies gathered" line when nothing is stopped but output waits.
        if (fullNodes.isEmpty() && pendingCollect > 0) {
            player.sendMessage(Component.text()
                    .append(Component.text("  Your crews gathered " + pendingCollect
                            + " resource(s) waiting to collect. ", NamedTextColor.AQUA))
                    .append(CommandLinks.run("[Open Nodes]", "/idle nodes"))
                    .build());
        }
        if (!fullNodes.isEmpty()) {
            var line = Component.text()
                    .append(Component.text("  " + fullNodes.size()
                            + " node buffer(s) full — production stopped. ",
                            NamedTextColor.YELLOW));
            if (fullNodes.size() == 1) {
                line.append(CommandLinks.run("[Collect]",
                                "/idle collect " + fullNodes.getFirst().getId()))
                        .append(Component.space());
            }
            line.append(CommandLinks.run("[Open Nodes]", "/idle nodes"));
            player.sendMessage(line.build());
        }
        if (claimable != null) {
            player.sendMessage(Component.text()
                    .append(Component.text("  Exploration result ready. ",
                            NamedTextColor.GREEN))
                    .append(CommandLinks.run("[Claim]",
                            "/idle explore claim " + claimable.getId()))
                    .build());
        }
        if (waiting != null) {
            player.sendMessage(Component.text()
                    .append(Component.text("  Exploration event waiting. ",
                            NamedTextColor.YELLOW))
                    .append(CommandLinks.run("[Open]",
                            "/idle explore info " + waiting.getId()))
                    .build());
        }
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
