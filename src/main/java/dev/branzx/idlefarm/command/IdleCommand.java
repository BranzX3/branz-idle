package dev.branzx.idlefarm.command;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public final class IdleCommand implements CommandExecutor, TabCompleter {

    private final IdleFarmPlugin plugin;
    private final PlayerDataStore dataStore;

    public IdleCommand(IdleFarmPlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String currencyName = plugin.getConfig().getString("currency-name", "Coins");

        if (args.length == 0 || args[0].equalsIgnoreCase("balance")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can check their balance.", NamedTextColor.RED));
                return true;
            }
            PlayerData data = dataStore.getOnline(player.getUniqueId());
            if (data == null) {
                sender.sendMessage(Component.text("Your data is still loading, try again in a moment.", NamedTextColor.YELLOW));
                return true;
            }
            sender.sendMessage(Component.text()
                    .append(Component.text("Balance: ", NamedTextColor.GRAY))
                    .append(Component.text(formatAmount(data.getBalance()) + " " + currencyName, NamedTextColor.GOLD))
                    .append(Component.text("  |  Online time: ", NamedTextColor.GRAY))
                    .append(Component.text(data.getTotalOnlineMinutes() + " min", NamedTextColor.AQUA))
                    .build());
            return true;
        }

        if (args[0].equalsIgnoreCase("top")) {
            int minMinutes = plugin.getConfig().getInt("top-min-minutes", 1);
            new BukkitRunnable() {
                @Override
                public void run() {
                    List<PlayerData> top = dataStore.loadTopSync(10, minMinutes);
                    sender.sendMessage(Component.text("=== IdleFarm Top 10 ===", NamedTextColor.YELLOW));
                    int rank = 1;
                    for (PlayerData data : top) {
                        sender.sendMessage(Component.text(
                                rank + ". " + data.getName() + " - " + formatAmount(data.getBalance()) + " " + currencyName,
                                NamedTextColor.WHITE));
                        rank++;
                    }
                }
            }.runTaskAsynchronously(plugin);
            return true;
        }

        if (args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("idlefarm.admin")) {
                sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
                return true;
            }
            if (args.length > 1 && args[1].equalsIgnoreCase("reload")) {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("IdleFarm config reloaded.", NamedTextColor.GREEN));
                return true;
            }
            sender.sendMessage(Component.text("Usage: /idle admin reload", NamedTextColor.YELLOW));
            return true;
        }

        sender.sendMessage(Component.text("Usage: /idle [balance|top|admin]", NamedTextColor.YELLOW));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("balance", "top", "admin");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("reload");
        }
        return List.of();
    }

    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }
}
