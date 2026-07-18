package dev.branzx.idlefarm.command;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.node.TrustLevel;
import dev.branzx.idlefarm.service.ClaimService;
import dev.branzx.idlefarm.service.TrustService;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;

public final class IdleCommand implements CommandExecutor, TabCompleter {

    private final IdleFarmPlugin plugin;
    private final PlayerDataStore dataStore;
    private final NodeStore nodeStore;
    private final ClaimService claimService;
    private final TrustService trustService;

    public IdleCommand(IdleFarmPlugin plugin, PlayerDataStore dataStore, NodeStore nodeStore,
                       ClaimService claimService, TrustService trustService) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.nodeStore = nodeStore;
        this.claimService = claimService;
        this.trustService = trustService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "balance" : args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "balance" -> balance(sender);
            case "top" -> top(sender);
            case "claim" -> claim(sender, args);
            case "unclaim" -> unclaim(sender);
            case "nodes" -> nodes(sender);
            case "trust" -> trust(sender, args);
            case "untrust" -> untrust(sender, args);
            case "admin" -> admin(sender, args);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "Usage: /idle [balance|top|claim <type>|unclaim|nodes|trust <player> <level>|untrust <player>|admin]",
                NamedTextColor.YELLOW));
        return true;
    }

    private boolean balance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can check their balance.", NamedTextColor.RED));
            return true;
        }
        PlayerData data = dataStore.getOnline(player.getUniqueId());
        if (data == null) {
            sender.sendMessage(Component.text("Your data is still loading, try again in a moment.", NamedTextColor.YELLOW));
            return true;
        }
        String currencyName = plugin.getConfig().getString("currency-name", "Coins");
        long production = claimService.countProductionNodes(player.getUniqueId());
        int cap = claimService.nodeCap(player.getUniqueId());
        sender.sendMessage(Component.text()
                .append(Component.text("Balance: ", NamedTextColor.GRAY))
                .append(Component.text(formatAmount(data.getBalance()) + " " + currencyName, NamedTextColor.GOLD))
                .append(Component.text("  |  Online: ", NamedTextColor.GRAY))
                .append(Component.text(data.getTotalOnlineMinutes() + " min", NamedTextColor.AQUA))
                .append(Component.text("  |  Nodes: ", NamedTextColor.GRAY))
                .append(Component.text(production + "/" + cap, NamedTextColor.GREEN))
                .build());
        return true;
    }

    private boolean top(CommandSender sender) {
        String currencyName = plugin.getConfig().getString("currency-name", "Coins");
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

    private boolean claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can claim.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /idle claim <residential|mining|farming|woodcutting|livestock|hunter>",
                    NamedTextColor.YELLOW));
            return true;
        }
        NodeType type = NodeType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown node type: " + args[1], NamedTextColor.RED));
            return true;
        }
        if (!claimService.isClaimableWorld(player.getWorld())) {
            sender.sendMessage(Component.text("Claims are not allowed in this world.", NamedTextColor.RED));
            return true;
        }
        ChunkKey chunk = new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);
        ClaimService.Result result = claimService.claim(player.getUniqueId(), chunk, type);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean unclaim(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can unclaim.", NamedTextColor.RED));
            return true;
        }
        ChunkKey chunk = new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);
        ClaimService.Result result = claimService.unclaim(player.getUniqueId(), chunk);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean nodes(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can list nodes.", NamedTextColor.RED));
            return true;
        }
        List<NodeRecord> owned = nodeStore.getByOwner(player.getUniqueId());
        if (owned.isEmpty()) {
            sender.sendMessage(Component.text("You have no nodes yet. Start with /idle claim residential",
                    NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("=== Your Nodes (" + owned.size() + ") ===", NamedTextColor.YELLOW));
        for (NodeRecord node : owned) {
            sender.sendMessage(Component.text(
                    node.getType() + " T" + node.getTier() + " @ " + node.getChunk().x() + "," + node.getChunk().z()
                            + " [" + node.getState() + "]",
                    NamedTextColor.WHITE));
        }
        return true;
    }

    private boolean trust(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can manage trust.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /idle trust <player> <visitor|helper|manager>",
                    NamedTextColor.YELLOW));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return true;
        }
        TrustLevel level = TrustLevel.fromString(args[2]);
        if (level == null) {
            sender.sendMessage(Component.text("Unknown trust level: " + args[2], NamedTextColor.RED));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            sender.sendMessage(Component.text("You cannot trust yourself.", NamedTextColor.RED));
            return true;
        }
        trustService.setTrust(player.getUniqueId(), target.getUniqueId(), level);
        sender.sendMessage(Component.text(
                "Granted " + level + " to " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean untrust(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can manage trust.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /idle untrust <player>", NamedTextColor.YELLOW));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        trustService.removeTrust(player.getUniqueId(), target.getUniqueId());
        sender.sendMessage(Component.text("Revoked trust from " + args[1] + ".", NamedTextColor.GREEN));
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("balance", "top", "claim", "unclaim", "nodes", "trust", "untrust", "admin");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("claim")) {
            return List.of("residential", "mining", "farming", "woodcutting", "livestock", "hunter");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("trust")) {
            return List.of("visitor", "helper", "manager");
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
