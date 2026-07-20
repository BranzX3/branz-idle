package dev.branzx.idlefarm.command;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.node.TrustLevel;
import dev.branzx.idlefarm.gui.GuiManager;
import dev.branzx.idlefarm.service.ClaimService;
import dev.branzx.idlefarm.service.GameDesignService;
import dev.branzx.idlefarm.service.TradeService;
import dev.branzx.idlefarm.service.TrustService;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.service.WorkerNpcManager;
import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
import org.bukkit.inventory.ItemStack;
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
    private final WorkerService workerService;
    private final WorkerStore workerStore;
    private final WorkerNpcManager npcManager;
    private final WarehouseService warehouseService;
    private final GuiManager guiManager;
    private final AdminCommands adminCommands;
    private TradeService tradeService;

    public IdleCommand(IdleFarmPlugin plugin, PlayerDataStore dataStore, NodeStore nodeStore,
                       ClaimService claimService, TrustService trustService,
                       WorkerService workerService, WorkerStore workerStore, WorkerNpcManager npcManager,
                       WarehouseService warehouseService, GuiManager guiManager,
                       AdminCommands adminCommands) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.nodeStore = nodeStore;
        this.warehouseService = warehouseService;
        this.guiManager = guiManager;
        this.claimService = claimService;
        this.trustService = trustService;
        this.workerService = workerService;
        this.workerStore = workerStore;
        this.npcManager = npcManager;
        this.adminCommands = adminCommands;
    }

    public void setTradeService(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Bare /idle opens the GUI hub for players.
        if (args.length == 0) {
            if (sender instanceof Player player) {
                guiManager.openMainHub(player);
                return true;
            }
            return balance(sender);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "balance" -> balance(sender);
            case "top" -> top(sender);
            case "claim" -> claim(sender, args);
            case "unclaim" -> unclaim(sender);
            case "nodes" -> nodes(sender);
            case "trust" -> trust(sender, args);
            case "untrust" -> untrust(sender, args);
            case "hire" -> hire(sender);
            case "bag" -> bag(sender);
            case "fuse" -> fuse(sender);
            case "assign" -> assign(sender);
            case "eject" -> eject(sender, args);
            case "skin" -> skin(sender, args);
            case "collect" -> collect(sender);
            case "explore" -> explore(sender, args);
            case "warehouse" -> warehouse(sender);
            case "map" -> map(sender);
            case "shop" -> shop(sender);
            case "convert" -> convert(sender, args);
            case "expedition" -> expedition(sender);
            case "visit" -> visit(sender, args);
            case "progress", "chronicle", "journal", "commissions", "projects" -> progress(sender);
            case "focus" -> focus(sender);
            case "commission" -> commission(sender, args);
            case "chapter" -> chapter(sender);
            case "project" -> project(sender, args);
            case "build" -> build(sender, args);
            case "trade" -> trade(sender, args);
            case "credits" -> credits(sender);
            case "admin" -> admin(sender, args);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text("» Type /idle to open the menu — everything is in there.",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  Extra: /idle visit <player>, /idle skin <name>"
                + (sender.hasPermission("idlefarm.admin") ? ", /idle admin" : ""),
                NamedTextColor.GRAY));
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
        ClaimService.Result result = claimService.claim(player.getUniqueId(), player.getWorld(), chunk, type);
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
        ClaimService.Result result = claimService.unclaim(player.getUniqueId(), player.getWorld(), chunk);
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
            String buffer = node.getType().isProduction()
                    ? "  buffer " + node.storageTotal() + "/" + (plugin.getConfig()
                            .getInt("production.buffer-capacity-per-tier", 64) * node.getTier())
                    : "";
            sender.sendMessage(Component.text(
                    node.getType() + " T" + node.getTier() + " @ " + node.getChunk().x() + "," + node.getChunk().z()
                            + " [" + node.getState() + "]" + buffer,
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

    private boolean hire(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can hire workers.", NamedTextColor.RED));
            return true;
        }
        WorkerService.Result result = workerService.hire(player.getUniqueId());
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (result.success() && result.item() != null) {
            giveOrDrop(player, result.item());
        }
        return true;
    }

    private boolean bag(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have a worker bag.", NamedTextColor.RED));
            return true;
        }
        guiManager.openWorkerBag(player);
        return true;
    }

    private boolean fuse(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can fuse workers.", NamedTextColor.RED));
            return true;
        }
        WorkerRecord held = workerService.fromItem(player.getInventory().getItemInMainHand());
        if (held == null) {
            sender.sendMessage(Component.text("Hold a worker contract to choose the fuse rarity.", NamedTextColor.RED));
            return true;
        }
        List<WorkerRecord> materials = new java.util.ArrayList<>();
        List<Integer> slots = new java.util.ArrayList<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && materials.size() < 2; slot++) {
            WorkerRecord record = workerService.fromItem(contents[slot]);
            if (record != null && record.getRarity() == held.getRarity()
                    && WorkerRecord.STATE_ITEM.equals(record.getState())) {
                materials.add(record);
                slots.add(slot);
            }
        }
        if (materials.size() < 2) {
            sender.sendMessage(Component.text("Need 2 " + held.getRarity()
                    + " workers in your inventory (found " + materials.size() + ").", NamedTextColor.RED));
            return true;
        }
        WorkerService.Result result = workerService.fuse(player.getUniqueId(), materials);
        // Remove only tokens whose authoritative DB/cache worker was
        // consumed. On failure the selected base survives.
        for (int index = 0; index < slots.size(); index++) {
            if (workerStore.get(materials.get(index).getWorkerUuid()) == null) {
                player.getInventory().setItem(slots.get(index), null);
            }
        }
        if (result.success() && result.item() != null) {
            giveOrDrop(player, result.item());
        }
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean assign(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can assign workers.", NamedTextColor.RED));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        WorkerRecord worker = workerService.fromItem(held);
        if (worker == null) {
            sender.sendMessage(Component.text("Hold a worker contract to assign.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !trustService.canManage(player.getUniqueId(), node.getOwnerUuid())) {
            sender.sendMessage(Component.text("Stand in a production node you can manage.", NamedTextColor.RED));
            return true;
        }
        WorkerService.Result result = workerService.assign(player.getUniqueId(), worker, node);
        if (result.success()) {
            held.setAmount(held.getAmount() - 1);
            npcManager.refreshNode(node, player.getWorld());
        }
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean eject(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can eject workers.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !trustService.canManage(player.getUniqueId(), node.getOwnerUuid())) {
            sender.sendMessage(Component.text("Stand in a production node you can manage.", NamedTextColor.RED));
            return true;
        }
        List<WorkerRecord> assigned = workerStore.getAssigned(node.getId());
        if (assigned.isEmpty()) {
            sender.sendMessage(Component.text("No workers assigned here.", NamedTextColor.RED));
            return true;
        }
        WorkerRecord target = null;
        if (args.length >= 2) {
            for (WorkerRecord worker : assigned) {
                if (worker.getName().equalsIgnoreCase(args[1])) {
                    target = worker;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(Component.text("No assigned worker named " + args[1] + ".", NamedTextColor.RED));
                return true;
            }
        } else {
            target = assigned.get(0);
        }
        WorkerService.Result result = workerService.eject(player.getUniqueId(), target);
        if (result.success()) {
            giveOrDrop(player, result.item());
            npcManager.refreshNode(node, player.getWorld());
        }
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean skin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can change worker skins.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("idlefarm.skin")) {
            sender.sendMessage(Component.text("Changing worker skins requires a rank.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /idle skin <playerName> (hold the worker contract)",
                    NamedTextColor.YELLOW));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        WorkerRecord worker = workerService.fromItem(held);
        if (worker == null) {
            sender.sendMessage(Component.text("Hold a worker contract to change its skin.", NamedTextColor.RED));
            return true;
        }
        WorkerService.Result result = workerService.setSkin(worker, args[1]);
        if (result.success() && result.item() != null) {
            player.getInventory().setItemInMainHand(result.item());
        }
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean collect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can collect.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
            return true;
        }
        // Helper trust may collect (goes to their inventory for now;
        // routed to the owner's Warehouse in the warehouse phase).
        if (!trustService.canHelp(player.getUniqueId(), node.getOwnerUuid())) {
            sender.sendMessage(Component.text("You are not trusted to collect here.", NamedTextColor.RED));
            return true;
        }
        if (node.getStorage().isEmpty()) {
            sender.sendMessage(Component.text("Nothing to collect yet.", NamedTextColor.YELLOW));
            return true;
        }
        // Collect-all routes to the owner's Warehouse, not inventory.
        int collected = collectToWarehouse(node);
        if (guiManager.gameDesignService() != null) {
            guiManager.gameDesignService().onBufferCollected(node, collected);
        }
        int remaining = node.storageTotal();
        node.setState("ACTIVE");
        nodeStore.updateProduction(node);
        npcManager.refreshNode(node, player.getWorld());
        if (remaining > 0) {
            sender.sendMessage(Component.text("Collected " + collected + " to Warehouse; "
                    + remaining + " left (warehouse full).", NamedTextColor.YELLOW));
        } else {
            sender.sendMessage(Component.text("Collected " + collected + " items to Warehouse.",
                    NamedTextColor.GREEN));
        }
        return true;
    }

    /** Moves node buffer into the owner's warehouse; returns amount stored. */
    private int collectToWarehouse(NodeRecord node) {
        return warehouseService.collectNode(node);
    }

    private boolean warehouse(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have a warehouse.", NamedTextColor.RED));
            return true;
        }
        guiManager.openWarehouse(player, player.getUniqueId());
        return true;
    }

    private boolean map(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can view the map.", NamedTextColor.RED));
            return true;
        }
        guiManager.openTerritoryMap(player);
        return true;
    }

    private boolean shop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can shop.", NamedTextColor.RED));
            return true;
        }
        guiManager.openShop(player);
        return true;
    }

    private boolean expedition(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can join the expedition.", NamedTextColor.RED));
            return true;
        }
        guiManager.openExpedition(player);
        return true;
    }

    private boolean progress(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        guiManager.openProgress(player);
        return true;
    }

    private boolean focus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        var chunk = player.getLocation().getChunk();
        NodeRecord node = nodeStore.getByChunk(new ChunkKey(
                chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        var design = guiManager.gameDesignService();
        if (design == null) {
            sender.sendMessage(Component.text("Progression service is not ready.", NamedTextColor.RED));
            return true;
        }
        var result = design.setFocus(player.getUniqueId(), node, false);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean commission(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 2) {
            sender.sendMessage(Component.text("Usage: /idle commission <focus|behavior|supply>",
                    NamedTextColor.YELLOW));
            return true;
        }
        var result = guiManager.gameDesignService().claimCommission(player.getUniqueId(), args[1]);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean chapter(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        var result = guiManager.gameDesignService().claimWeeklyChapter(player.getUniqueId());
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean project(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /idle project <storehouse|expedition_dock|chronicle_hall> [amount]",
                    NamedTextColor.YELLOW));
            return true;
        }
        int amount = 64;
        if (args.length >= 3) {
            try { amount = Integer.parseInt(args[2]); }
            catch (NumberFormatException ignored) { amount = 64; }
        }
        var result = "server".equalsIgnoreCase(args[1])
                ? guiManager.gameDesignService().contributeServerProject(player.getUniqueId(), amount)
                : guiManager.gameDesignService()
                        .contributeProject(player.getUniqueId(), args[1], amount);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean build(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 3) {
            sender.sendMessage(Component.text(
                    "Usage: /idle build <specialization|refinement|mastery|perk15|perk35|perk60|perk85> <choice>",
                    NamedTextColor.YELLOW));
            return true;
        }
        var chunk = player.getLocation().getChunk();
        NodeRecord node = nodeStore.getByChunk(new ChunkKey(
                chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (node == null) {
            sender.sendMessage(Component.text("Stand inside your Production Node.", NamedTextColor.RED));
            return true;
        }
        var design = guiManager.gameDesignService();
        GameDesignService.Result result;
        String tier = args[1].toLowerCase(Locale.ROOT);
        if (tier.startsWith("perk")) {
            int level;
            try { level = Integer.parseInt(tier.substring(4)); }
            catch (NumberFormatException e) { level = -1; }
            result = design.selectTypePerk(player.getUniqueId(), node, level, args[2]);
        } else {
            result = design.selectBuild(player.getUniqueId(), node, tier, args[2]);
        }
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean visit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can visit.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /idle visit <player|toggle>", NamedTextColor.YELLOW));
            return true;
        }
        var perks = guiManager.perkService();
        if (args[1].equalsIgnoreCase("toggle")) {
            boolean nowClosed = !perks.has(player.getUniqueId(),
                    dev.branzx.idlefarm.service.PerkService.NO_VISITS);
            perks.setFlag(player.getUniqueId(),
                    dev.branzx.idlefarm.service.PerkService.NO_VISITS, nowClosed);
            sender.sendMessage(Component.text(nowClosed
                    ? "Your territory is now CLOSED to visitors."
                    : "Your territory is now OPEN to visitors.", NamedTextColor.GREEN));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return true;
        }
        if (perks.has(target.getUniqueId(), dev.branzx.idlefarm.service.PerkService.NO_VISITS)) {
            sender.sendMessage(Component.text(args[1] + "'s territory is closed to visitors.",
                    NamedTextColor.RED));
            return true;
        }
        NodeRecord home = nodeStore.getByOwner(target.getUniqueId()).stream()
                .filter(n -> n.getType() == NodeType.RESIDENTIAL)
                .findFirst().orElse(null);
        if (home == null) {
            sender.sendMessage(Component.text(args[1] + " has no territory to visit.", NamedTextColor.RED));
            return true;
        }
        var world = Bukkit.getWorld(home.getChunk().world());
        if (world == null) {
            sender.sendMessage(Component.text("Their world is not loaded.", NamedTextColor.RED));
            return true;
        }
        int x = (home.getChunk().x() << 4) + 8;
        int z = (home.getChunk().z() << 4) + 8;
        int y = world.getHighestBlockYAt(x, z) + 1;
        player.teleport(new org.bukkit.Location(world, x + 0.5, y, z + 0.5));
        sender.sendMessage(Component.text("Visiting " + args[1] + "'s territory. Look, don't touch!",
                NamedTextColor.GREEN));
        return true;
    }

    private boolean convert(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can convert nodes.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /idle convert <mining|farming|woodcutting|livestock|hunter>",
                    NamedTextColor.YELLOW));
            return true;
        }
        NodeType type = NodeType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown node type: " + args[1], NamedTextColor.RED));
            return true;
        }
        ChunkKey chunk = new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);
        ClaimService.Result result = claimService.convert(player.getUniqueId(), player.getWorld(), chunk, type);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean explore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can explore.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
            return true;
        }
        var exploration = plugin.getExplorationService();
        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "info";

        switch (action) {
            case "info" -> {
                int bracket = exploration.bracket(node);
                sender.sendMessage(Component.text("Exploration Lv." + node.getExplorationLevel()
                        + " (Bracket " + toRoman(bracket) + ")  exp "
                        + node.getExplorationExp() + "/"
                        + exploration.expForNextExplorationLevel(node.getExplorationLevel()),
                        NamedTextColor.AQUA));
                var event = exploration.getEvent(node.getId());
                if (event == null) {
                    sender.sendMessage(Component.text("No event at this node right now.", NamedTextColor.GRAY));
                } else {
                    long now = System.currentTimeMillis();
                    String detail = switch (event.getState()) {
                        case "AVAILABLE" -> "waiting — expires in "
                                + Math.max(0, (event.getExpiresAt() - now) / 60000) + "m. /idle explore start [team]";
                        case "RUNNING" -> "team away — returns in "
                                + Math.max(0, (event.getEndsAt() - now) / 60000) + "m";
                        default -> event.getGrade() + " result ready! /idle explore claim";
                    };
                    sender.sendMessage(Component.text(exploration.eventName(event.getEventType())
                            + " [" + event.getState() + "] " + detail, NamedTextColor.YELLOW));
                }
            }
            case "prepare" -> {
                String option = args.length >= 3 ? args[2] : "";
                var result = guiManager.gameDesignService()
                        .prepareExpedition(player.getUniqueId(), node, option);
                sender.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "start" -> {
                if (!trustService.canManage(player.getUniqueId(), node.getOwnerUuid())) {
                    sender.sendMessage(Component.text("Manager trust required.", NamedTextColor.RED));
                    return true;
                }
                int team = args.length >= 3 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
                String error = exploration.start(node, team);
                if (error != null) {
                    sender.sendMessage(Component.text(error, NamedTextColor.RED));
                } else {
                    npcManager.refreshNode(node, player.getWorld());
                    sender.sendMessage(Component.text("Expedition sent!", NamedTextColor.GREEN));
                }
            }
            case "claim" -> {
                if (!trustService.canManage(player.getUniqueId(), node.getOwnerUuid())) {
                    sender.sendMessage(Component.text("Manager trust required.", NamedTextColor.RED));
                    return true;
                }
                var result = exploration.claimToWarehouse(node, warehouseService);
                sender.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GOLD : NamedTextColor.RED));
                if (result.success()) {
                    npcManager.refreshNode(node, player.getWorld());
                }
            }
            default -> sender.sendMessage(Component.text(
                    "Usage: /idle explore [info|prepare <speed|quantity|research>|start [team]|claim]",
                    NamedTextColor.YELLOW));
        }
        return true;
    }

    private String toRoman(int number) {
        String[] romans = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return number >= 1 && number <= 10 ? romans[number - 1] : String.valueOf(number);
    }

    private NodeRecord nodeAt(Player player) {
        return nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4));
    }

    private void giveOrDrop(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private boolean trade(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || tradeService == null) {
            sender.sendMessage(Component.text("Protected trade is available to online players.",
                    NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text(
                    "Usage: /idle trade <player|accept|offer|view|confirm|cancel> [player]",
                    NamedTextColor.YELLOW));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        TradeService.Result result;
        switch (action) {
            case "accept" -> {
                if (args.length < 3 || Bukkit.getPlayerExact(args[2]) == null) {
                    result = new TradeService.Result(false, "Usage: /idle trade accept <player>");
                } else {
                    Player requester = Bukkit.getPlayerExact(args[2]);
                    result = tradeService.accept(player, requester);
                    if (result.success()) {
                        guiManager.openTrade(player);
                        guiManager.openTrade(requester);
                    }
                }
            }
            case "offer" -> result = tradeService.offerHeld(player);
            case "confirm" -> result = tradeService.confirm(player);
            case "cancel" -> result = tradeService.cancel(player);
            case "view" -> {
                TradeService.View view = tradeService.view(player.getUniqueId());
                if (view == null) {
                    result = new TradeService.Result(false, "You are not in a trade.");
                } else {
                    guiManager.openTrade(player);
                    return true;
                }
            }
            default -> {
                Player target = Bukkit.getPlayerExact(args[1]);
                result = target == null
                        ? new TradeService.Result(false, "That player is not online.")
                        : tradeService.request(player, target);
            }
        }
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean credits(CommandSender sender) {
        if (!(sender instanceof Player player) || guiManager.creditService() == null) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        long balance = guiManager.creditService().balance(player.getUniqueId());
        sender.sendMessage(Component.text("Credits: " + balance
                + " (integer, non-transferable, non-cashable)", NamedTextColor.LIGHT_PURPLE));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            var history = guiManager.creditService().historySync(player.getUniqueId(), 5);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (history.isEmpty()) {
                    sender.sendMessage(Component.text("No Credit purchase/spend history.",
                            NamedTextColor.GRAY));
                } else {
                    history.forEach(entry -> sender.sendMessage(Component.text(
                            entry.createdAt() + " " + entry.type() + " "
                                    + (entry.amount() >= 0 ? "+" : "") + entry.amount()
                                    + " [" + entry.transactionId() + "]", NamedTextColor.GRAY)));
                }
            });
        });
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("idlefarm.admin")) {
            sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        return adminCommands.handle(sender, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            // Everything lives in the /idle GUI; only surface the few commands
            // that need typed arguments (plus admin for staff).
            List<String> base = new java.util.ArrayList<>(List.of(
                    "progress", "focus", "commission", "chapter", "project",
                    "build", "trade", "credits", "visit", "skin"));
            if (sender.hasPermission("idlefarm.admin")) {
                base.add("admin");
            }
            return base;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return List.of("reload", "schem", "npc", "node", "pool", "event", "explevel",
                    "give", "setcap", "credits", "validate", "metrics",
                    "claims", "forceunclaim", "audit");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("event")) {
            return List.of("spawn", "cancel", "list");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("pool")) {
            return List.of("mining", "farming", "woodcutting", "livestock", "hunter", "rollback");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("commission")) {
            return List.of("focus", "behavior", "supply", "catchup");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("project")) {
            return List.of("storehouse", "expedition_dock", "chronicle_hall", "server");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("trade")) {
            return List.of("accept", "offer", "view", "confirm", "cancel");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("give")) {
            return List.of("money", "item");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("schem")) {
            return List.of("capture", "edit", "setspawn", "setwork", "setwander", "setanim", "save", "rebuild");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("npc")) {
            return List.of("refresh", "list", "state");
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("npc")
                && args[2].equalsIgnoreCase("state")) {
            return List.of("working", "idle", "stop", "clear");
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
