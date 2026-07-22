package dev.branzx.idle.command;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.node.TrustLevel;
import dev.branzx.idle.gui.GuiManager;
import dev.branzx.idle.service.ClaimService;
import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.service.TradeService;
import dev.branzx.idle.service.TrustService;
import dev.branzx.idle.service.WarehouseService;
import dev.branzx.idle.service.WorkerNpcManager;
import dev.branzx.idle.service.WorkerService;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.WorkerRecord;
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

    private final IdlePlugin plugin;
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
    private final TradeService tradeService;

    public IdleCommand(IdlePlugin plugin, PlayerDataStore dataStore, NodeStore nodeStore,
                       ClaimService claimService, TrustService trustService,
                       WorkerService workerService, WorkerStore workerStore, WorkerNpcManager npcManager,
                       WarehouseService warehouseService, GuiManager guiManager,
                       AdminCommands adminCommands, TradeService tradeService) {
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
        this.tradeService = tradeService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            return dispatch(sender, args);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid number: " + e.getMessage(),
                    NamedTextColor.RED));
            return true;
        }
    }

    private boolean dispatch(CommandSender sender, String[] args) {
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
            case "help" -> help(sender, args);
            case "balance" -> balance(sender);
            case "top" -> top(sender);
            case "claim" -> claim(sender, args);
            case "rotate" -> rotate(sender, args);
            case "building" -> buildingSkin(sender, args);
            case "submit" -> submitSkin(sender, args);
            case "merge" -> merge(sender, args);
            case "unmerge" -> unmerge(sender);
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
            case "collect" -> collect(sender, args);
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
            case "frontier" -> frontier(sender, args);
            case "trade" -> trade(sender, args);
            case "credits" -> credits(sender);
            case "admin" -> admin(sender, args);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        return help(sender, new String[]{"help"});
    }

    private boolean help(CommandSender sender, String[] args) {
        String filter = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : null;
        sender.sendMessage(Component.text("Idle Commands", NamedTextColor.GOLD));
        if (filter == null) {
            sender.sendMessage(Component.text("ผู้เล่น: ", NamedTextColor.GRAY)
                    .append(CommandLinks.run("[เปิด Hub]", "/idle")));
            Component categories = Component.text("หมวด: ", NamedTextColor.GRAY);
            for (String category : CommandCatalog.categories(CommandCatalog.Audience.PLAYER)) {
                categories = categories.append(CommandLinks.run(
                        "[" + category + "]", "/idle help " + category)).append(Component.space());
            }
            sender.sendMessage(categories);
            if (hasAnyAdminPermission(sender)) {
                sender.sendMessage(Component.text("ผู้ดูแล: ", NamedTextColor.RED)
                        .append(CommandLinks.run("[เปิด Admin Hub]", "/idle admin")));
            }
            return true;
        }
        List<CommandCatalog.Entry> entries =
                CommandCatalog.inCategory(CommandCatalog.Audience.PLAYER, filter);
        if (entries.isEmpty()) {
            CommandCatalog.Entry entry = CommandCatalog.findPlayer(filter);
            entries = entry == null ? List.of() : List.of(entry);
        }
        entries = entries.stream()
                .filter(entry -> entry.permission() == null || sender.hasPermission(entry.permission()))
                .toList();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("ไม่พบ command หรือหมวด '" + filter + "'.",
                    NamedTextColor.RED));
            return true;
        }
        for (CommandCatalog.Entry entry : entries) {
            String suffix = entry.syntax().isBlank() ? "" : " " + entry.syntax();
            String command = "/idle " + entry.name();
            boolean runsWithoutArguments = entry.syntax().isBlank()
                    || (entry.syntax().startsWith("[") && !entry.name().equals("trade"));
            Component link = runsWithoutArguments
                    ? CommandLinks.run(command, command)
                    : CommandLinks.suggest(command + suffix, command + " ");
            sender.sendMessage(link
                    .append(Component.text(" — " + entry.description(), NamedTextColor.GRAY)));
        }
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
                sender.sendMessage(Component.text("=== Idle Top 10 ===", NamedTextColor.YELLOW));
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
                    "Usage: /idle claim <residential|mining|farming|woodcutting|livestock|hunter>"
                            + " | /idle claim cancel",
                    NamedTextColor.YELLOW));
            return true;
        }
        if (args[1].equalsIgnoreCase("confirm")) {
            return claimConfirm(player, args.length >= 3 ? args[2] : "");
        }
        if (args[1].equalsIgnoreCase("cancel")) {
            return claimCancel(player);
        }
        if (args[1].equalsIgnoreCase("rotate")) {
            return previewRotate(player, args.length >= 3 ? args[2] : "");
        }
        NodeType type = NodeType.fromString(args[1]);
        if (type == null) {
            sender.sendMessage(Component.text("Unknown node type: " + args[1] + " ",
                    NamedTextColor.RED)
                    .append(CommandLinks.run("[Open Map]", "/idle map")));
            return true;
        }
        if (!claimService.isClaimableWorld(player.getWorld())) {
            sender.sendMessage(Component.text("Claims are not allowed in this world.", NamedTextColor.RED));
            return true;
        }
        ChunkKey chunk = new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4);

        var preview = plugin.getPreviewService();
        // Residential plots place no building, so there is nothing to preview.
        boolean previewable = type.isProduction() && preview != null
                && plugin.getConfig().getBoolean("nodes.preview.enabled", true);
        if (!previewable) {
            ClaimService.Result result =
                    claimService.claim(player.getUniqueId(), player.getWorld(), chunk, type);
            sender.sendMessage(Component.text(result.message(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            return true;
        }

        // Check the rules before drawing anything: showing a building the
        // player cannot afford or place is worse than a plain refusal.
        ClaimService.Quote quote =
                claimService.quote(player.getUniqueId(), player.getWorld(), chunk, type);
        if (!quote.allowed()) {
            sender.sendMessage(Component.text(quote.message(), NamedTextColor.RED));
            return true;
        }
        var definition = plugin.getSchematicService().getRegistry().forNodeType(type, 1);
        int originY = plugin.getSchematicService().groundY(player.getWorld(), chunk, definition);
        var session = preview.open(player, chunk, type, definition, originY);
        sendPreviewPrompt(player, session, quote);
        return true;
    }

    /**
     * The preview prompt. The confirm link carries the session token, so a
     * line re-clicked after the session ended is inert rather than a second
     * claim — the chat-action staleness rule for a spending verb.
     */
    private void sendPreviewPrompt(Player player, dev.branzx.idle.service.PreviewService.Session session,
                                   ClaimService.Quote quote) {
        player.sendMessage(Component.text("[Idle] Placement preview — "
                + session.type() + " at chunk " + session.chunk().x() + ","
                + session.chunk().z() + ".", NamedTextColor.GOLD));
        player.sendMessage(Component.text("  Cost: "
                + (quote.tutorialFree() ? "FREE (tutorial)" : String.valueOf(quote.cost()))
                + " • Ground Y: " + session.originY(), NamedTextColor.GRAY));
        if (session.obstructions() > 0) {
            player.sendMessage(Component.text("  " + session.obstructions()
                    + " solid block(s) shown in red overlap the building. They will NOT be "
                    + "cleared — move, or clear them first.", NamedTextColor.RED));
        }
        player.sendMessage(Component.text("  Trees and plants are cleared automatically and "
                + "restored if you unclaim.", NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.text()
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(CommandLinks.run("[Rotate]", "/idle claim rotate " + session.token()))
                .append(Component.space())
                .append(CommandLinks.run("[Confirm]", "/idle claim confirm " + session.token()))
                .append(Component.space())
                .append(CommandLinks.run("[Cancel]", "/idle claim cancel"))
                .build());
    }

    private static String facing(int rotation) {
        return switch (dev.branzx.idle.schematic.Rotation.normalize(rotation)) {
            case 1 -> "South";
            case 2 -> "West";
            case 3 -> "North";
            default -> "East";
        };
    }

    /**
     * Turns the pending building without ending the session, so the player can
     * cycle orientations before deciding. Serves both a fresh claim and a
     * re-orientation of a placed building.
     */
    /**
     * Quarter-turns per press. A Complex whose land only supports two
     * orientations skips over the ones it cannot take, so the button never
     * offers an illegal turn.
     */
    private int rotationStep(NodeRecord node) {
        var complex = plugin.getComplexService();
        if (complex == null || !node.isInComplex()) {
            return 1;
        }
        int allowed = complex.allowedRotations(node).size();
        return allowed <= 0 ? 1 : 4 / allowed;
    }

    private boolean previewRotate(Player player, String token) {
        var preview = plugin.getPreviewService();
        var current = preview == null ? null : preview.get(player);
        int step = 1;
        java.util.function.IntFunction<java.util.List<
                dev.branzx.idle.service.PreviewService.Part>> partsFor = null;
        if (current != null && current.nodeId() > 0) {
            NodeRecord node = nodeStore.getById(current.nodeId());
            var complex = plugin.getComplexService();
            if (node != null) {
                step = rotationStep(node);
                if (complex != null && node.isInComplex()) {
                    NodeRecord anchor = node;
                    // The pieces relocate as the Complex turns, so they are
                    // recomputed for whatever orientation this press lands on.
                    partsFor = rotation -> complex.planRotation(anchor, rotation);
                }
            }
        }
        var session = preview == null ? null : preview.rotate(player, token, step, partsFor);
        if (session == null) {
            player.sendMessage(Component.text("That placement preview is no longer active. ",
                    NamedTextColor.YELLOW)
                    .append(CommandLinks.suggest("[Claim again]", "/idle claim ")));
            return true;
        }
        player.sendMessage(Component.text("  Facing: " + facing(session.rotation())
                + (session.obstructions() > 0
                        ? " • " + session.obstructions() + " block(s) in the way" : " • clear"),
                session.obstructions() > 0 ? NamedTextColor.RED : NamedTextColor.GRAY));
        player.sendMessage(Component.text()
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(CommandLinks.run("[Rotate]",
                        (session.isExistingNode() ? "/idle rotate rotate " : "/idle claim rotate ")
                                + session.token()))
                .append(Component.space())
                .append(CommandLinks.run("[Confirm]",
                        (session.isExistingNode() ? "/idle rotate confirm " : "/idle claim confirm ")
                                + session.token()))
                .append(Component.space())
                .append(CommandLinks.run("[Cancel]",
                        session.isExistingNode() ? "/idle rotate cancel" : "/idle claim cancel"))
                .build());
        return true;
    }

    /**
     * Skin selection for the node the player is standing on.
     *
     * <p>Applying is a Tier B action but carries no cost and is fully
     * reversible, so it runs from chat directly; the confirmation the player
     * needs is seeing the building change, which they do immediately.</p>
     */
    private boolean buildingSkin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can change building skins.",
                    NamedTextColor.RED));
            return true;
        }
        var registry = plugin.getSkinRegistry();
        var unlocks = plugin.getPlayerSkinStore();
        if (registry == null || unlocks == null) {
            sender.sendMessage(Component.text("Skins are not available.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4));
        if (node == null || !node.getOwnerUuid().equals(player.getUniqueId())) {
            sender.sendMessage(Component.text("Stand on a node you own to change its skin.",
                    NamedTextColor.RED));
            return true;
        }
        if (!node.getType().isProduction()) {
            sender.sendMessage(Component.text("Only production nodes have a building.",
                    NamedTextColor.RED));
            return true;
        }

        if (args.length >= 2) {
            String requested = args[1];
            if (requested.equalsIgnoreCase("default") || requested.equalsIgnoreCase("none")) {
                var result = claimService.applySkin(player.getUniqueId(), player.getWorld(),
                        node, null);
                sender.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                return true;
            }
            var skin = registry.get(requested);
            if (skin == null || !skin.appliesTo(node.getType())) {
                sender.sendMessage(Component.text("No skin '" + requested
                        + "' available for this node type. ", NamedTextColor.RED)
                        .append(CommandLinks.run("[List skins]", "/idle building")));
                return true;
            }
            // Re-checked here rather than trusting the listing: the listing may
            // be an old chat line, and unlocks can be revoked.
            if (!unlocks.canUse(player.getUniqueId(), skin)) {
                sender.sendMessage(Component.text("You have not unlocked "
                        + skin.getDisplay() + ".", NamedTextColor.RED));
                return true;
            }
            var result = claimService.applySkin(player.getUniqueId(), player.getWorld(),
                    node, skin.getId());
            sender.sendMessage(Component.text(result.message(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            return true;
        }

        var available = registry.forType(node.getType());
        if (available.isEmpty()) {
            sender.sendMessage(Component.text("No skins exist for " + node.getType()
                    + " nodes yet.", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("[Idle] Skins for node #" + node.getId()
                + " (" + node.getType() + "):", NamedTextColor.GOLD));
        String current = node.getSkinId();
        sender.sendMessage(Component.text()
                .append(Component.text("  " + (current == null ? "▶ " : "  ") + "Default",
                        current == null ? NamedTextColor.GREEN : NamedTextColor.GRAY))
                .append(Component.space())
                .append(CommandLinks.run("[Use]", "/idle building default"))
                .build());
        for (var skin : available) {
            boolean owned = unlocks.canUse(player.getUniqueId(), skin);
            boolean worn = skin.getId().equalsIgnoreCase(current);
            var line = Component.text()
                    .append(Component.text("  " + (worn ? "▶ " : "  ") + skin.getDisplay(),
                            worn ? NamedTextColor.GREEN
                                    : owned ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
            // Author credit is the contest prize, so it is always shown.
            if (skin.getAuthorName() != null) {
                line.append(Component.text(" by " + skin.getAuthorName(), NamedTextColor.AQUA));
            }
            if (!owned) {
                line.append(Component.text(" (locked)", NamedTextColor.RED));
            } else if (!worn) {
                line.append(Component.space())
                        .append(CommandLinks.run("[Use]", "/idle building " + skin.getId()));
            }
            sender.sendMessage(line.build());
        }
        return true;
    }

    /**
     * Contest submission: capture a building the player made on their own
     * node, with the anchors they authored, into the pending review queue.
     */
    private boolean submitSkin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can submit builds.", NamedTextColor.RED));
            return true;
        }
        var submissions = plugin.getSkinSubmissionService();
        if (submissions == null
                || !plugin.getConfig().getBoolean("skins.submissions-open", true)) {
            sender.sendMessage(Component.text("Build submissions are closed right now.",
                    NamedTextColor.YELLOW));
            return true;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        var draft = submissions.draft(player);

        if (action.equals("cancel")) {
            submissions.cancel(player);
            sender.sendMessage(Component.text("Submission draft discarded.", NamedTextColor.YELLOW));
            return true;
        }

        if (action.isEmpty() || action.equals("start")) {
            NodeRecord node = nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                    player.getLocation().getBlockX() >> 4,
                    player.getLocation().getBlockZ() >> 4));
            if (node == null || !node.getOwnerUuid().equals(player.getUniqueId())) {
                sender.sendMessage(Component.text(
                        "Stand on a node you own, on the block level your build sits on.",
                        NamedTextColor.RED));
                return true;
            }
            // A merged Complex submits as one design across all its chunks;
            // membership already proves the player owns every one of them.
            var complex = plugin.getComplexService();
            java.util.List<ChunkKey> chunks = java.util.List.of(node.getChunk());
            dev.branzx.idle.complex.ComplexShape shape = null;
            if (complex != null && node.isInComplex()) {
                var members = complex.members(node);
                if (!members.isEmpty()) {
                    chunks = members.stream().map(NodeRecord::getChunk).toList();
                    shape = complex.shapeOf(node);
                    node = complex.anchorOf(node) == null ? node : complex.anchorOf(node);
                }
            }
            var started = submissions.begin(player, node, chunks, shape);
            var limits = shape == null ? submissions.limits() : submissions.pieceLimits();
            sender.sendMessage(Component.text("[Idle] Build submission started.",
                    NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  Base Y " + started.baseY()
                    + " (where you stand). " + (shape == null
                            ? "Only this chunk is captured."
                            : "Capturing all " + chunks.size() + " chunks of your "
                                    + shape.id() + " Complex."), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Limit per chunk: " + limits.maxWidth() + "×"
                    + limits.maxDepth() + ", height " + limits.maxHeight() + ", "
                    + limits.maxBlocks() + " blocks.", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Stand where each worker should stand, then "
                    + "/idle submit spawn <slot> and /idle submit work <slot>.",
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text()
                    .append(Component.text("  ", NamedTextColor.GRAY))
                    .append(CommandLinks.suggest("[Finish]", "/idle submit done "))
                    .append(Component.space())
                    .append(CommandLinks.run("[Cancel]", "/idle submit cancel"))
                    .build());
            return true;
        }

        if (draft == null) {
            sender.sendMessage(Component.text("Start a submission first. ", NamedTextColor.YELLOW)
                    .append(CommandLinks.run("[Start]", "/idle submit start")));
            return true;
        }

        if (action.equals("spawn") || action.equals("work")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /idle submit " + action + " <slot>",
                        NamedTextColor.YELLOW));
                return true;
            }
            int slot = Integer.parseInt(args[2]);
            if (slot < 1 || slot > 5) {
                sender.sendMessage(Component.text("Slot must be 1-5 (a node has one slot per tier).",
                        NamedTextColor.RED));
                return true;
            }
            var pos = submissions.setAnchor(player, draft, slot, action.equals("work"));
            sender.sendMessage(Component.text("  " + action + " slot " + slot + " set to "
                    + pos.x() + "," + pos.y() + "," + pos.z() + " (relative to your build).",
                    NamedTextColor.GREEN));
            return true;
        }

        if (action.equals("done")) {
            if (args.length < 3) {
                sender.sendMessage(Component.text("Usage: /idle submit done <name>",
                        NamedTextColor.YELLOW));
                return true;
            }
            String name = String.join("_",
                    java.util.Arrays.copyOfRange(args, 2, args.length));
            var result = submissions.submit(player, draft, name);
            sender.sendMessage(Component.text(result.message(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            // Every failed rule is listed at once; fixing one at a time across
            // several captures would be miserable for a large build.
            for (var violation : result.violations()) {
                sender.sendMessage(Component.text("  • " + violation.rule() + " — "
                        + violation.detail(), NamedTextColor.RED));
            }
            if (result.success()) {
                sender.sendMessage(Component.text(
                        "  An admin will review it. Your name ships with the skin.",
                        NamedTextColor.GRAY));
            }
            return true;
        }

        sender.sendMessage(Component.text(
                "Usage: /idle submit [start|spawn <slot>|work <slot>|done <name>|cancel]",
                NamedTextColor.YELLOW));
        return true;
    }

    /**
     * Merges the Production node the player stands on with the Residential
     * plots around it, so one building can span several chunks.
     */
    private boolean merge(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can merge nodes.", NamedTextColor.RED));
            return true;
        }
        var complex = plugin.getComplexService();
        if (complex == null) {
            sender.sendMessage(Component.text("Complexes are not available.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4));
        if (node == null || !node.getOwnerUuid().equals(player.getUniqueId())) {
            sender.sendMessage(Component.text("Stand on a production node you own.",
                    NamedTextColor.RED));
            return true;
        }
        if (node.isInComplex()) {
            var shape = complex.shapeOf(node);
            sender.sendMessage(Component.text("This node is already part of a "
                    + (shape == null ? "" : shape.id() + " ") + "Complex. ", NamedTextColor.YELLOW)
                    .append(CommandLinks.run("[Unmerge]", "/idle unmerge")));
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            return mergeConfirm(player, args.length >= 3 ? args[2] : "");
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            var preview = plugin.getPreviewService();
            if (preview != null) {
                preview.end(player);
            }
            sender.sendMessage(Component.text("Merge cancelled; nothing was charged.",
                    NamedTextColor.YELLOW));
            return true;
        }

        if (args.length >= 2) {
            var shape = dev.branzx.idle.complex.ComplexShape.parse(args[1]);
            if (shape == null) {
                sender.sendMessage(Component.text("Unknown shape '" + args[1]
                        + "'. Try /idle merge to see what your land supports.",
                        NamedTextColor.RED));
                return true;
            }
            var placement = complex.placementFor(node, shape);
            if (placement == null) {
                sender.sendMessage(Component.text("You do not own the Residential plots a "
                        + shape.id() + " Complex needs (" + shape.residentialNeeded()
                        + " adjacent).", NamedTextColor.RED));
                return true;
            }
            var preview = plugin.getPreviewService();
            if (preview == null
                    || !plugin.getConfig().getBoolean("nodes.preview.enabled", true)) {
                var result = complex.merge(player.getUniqueId(), player.getWorld(), node, shape);
                sender.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                return true;
            }
            var session = preview.openMerge(player, node,
                    complex.planPreview(node, placement));
            sender.sendMessage(Component.text("[Idle] Merge preview — " + shape.id()
                    + " Complex, " + shape.blockWidth() + "×" + shape.blockDepth() + " blocks.",
                    NamedTextColor.GOLD));
            sender.sendMessage(Component.text("  Cost " + complex.mergeCost(shape)
                    + " • uses " + shape.residentialNeeded() + " Residential plot(s)",
                    NamedTextColor.GRAY));
            if (session.obstructions() > 0) {
                sender.sendMessage(Component.text("  " + session.obstructions()
                        + " solid block(s) shown in red will NOT be cleared.",
                        NamedTextColor.RED));
            }
            sender.sendMessage(Component.text(
                    "  Production is unchanged. Your plots return untouched if you unmerge.",
                    NamedTextColor.DARK_GRAY));
            sender.sendMessage(Component.text()
                    .append(Component.text("  ", NamedTextColor.GRAY))
                    .append(CommandLinks.run("[Confirm]", "/idle merge confirm " + session.token()))
                    .append(Component.space())
                    .append(CommandLinks.run("[Cancel]", "/idle merge cancel"))
                    .build());
            return true;
        }

        var options = complex.options(node);
        if (options.isEmpty()) {
            sender.sendMessage(Component.text(
                    "No Complex fits here yet. Claim Residential plots next to this node — "
                            + "they become the building's floor space.", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("[Idle] Complex shapes your land supports:",
                NamedTextColor.GOLD));
        // Distinct shapes only: several offsets of the same shape are the same
        // offer to the player, who picks a size and not a corner.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var option : options) {
            if (!seen.add(option.shape().id())) {
                continue;
            }
            var shape = option.shape();
            sender.sendMessage(Component.text()
                    .append(Component.text("  " + shape.id() + " — "
                            + shape.blockWidth() + "×" + shape.blockDepth() + " blocks, "
                            + shape.residentialNeeded() + " residential, "
                            + complex.mergeCost(shape) + " Coins ", NamedTextColor.WHITE))
                    .append(CommandLinks.run("[Merge]", "/idle merge " + shape.id()))
                    .build());
        }
        sender.sendMessage(Component.text(
                "  Production is unchanged — a Complex is a bigger building, not a bigger yield.",
                NamedTextColor.DARK_GRAY));
        return true;
    }

    /**
     * Commits a previewed merge. The shape is derived from the chunks the
     * preview covered, and every rule is checked again by the service — the
     * token only proves the player saw the offer.
     */
    private boolean mergeConfirm(Player player, String token) {
        var preview = plugin.getPreviewService();
        var complex = plugin.getComplexService();
        var session = preview == null ? null : preview.consume(player, token);
        if (session == null
                || session.kind() != dev.branzx.idle.service.PreviewService.Kind.MERGE
                || complex == null) {
            player.sendMessage(Component.text("That merge preview is no longer active. ",
                    NamedTextColor.YELLOW)
                    .append(CommandLinks.run("[Merge]", "/idle merge")));
            return true;
        }
        NodeRecord node = nodeStore.getById(session.nodeId());
        if (node == null) {
            player.sendMessage(Component.text("That node no longer exists.", NamedTextColor.RED));
            return true;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var part : session.parts()) {
            minX = Math.min(minX, part.chunk().x());
            maxX = Math.max(maxX, part.chunk().x());
            minZ = Math.min(minZ, part.chunk().z());
            maxZ = Math.max(maxZ, part.chunk().z());
        }
        var shape = new dev.branzx.idle.complex.ComplexShape(maxX - minX + 1, maxZ - minZ + 1);
        var result = complex.merge(player.getUniqueId(), player.getWorld(), node, shape);
        player.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean unmerge(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can unmerge nodes.", NamedTextColor.RED));
            return true;
        }
        var complex = plugin.getComplexService();
        if (complex == null) {
            sender.sendMessage(Component.text("Complexes are not available.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4));
        if (node == null || !node.getOwnerUuid().equals(player.getUniqueId())) {
            sender.sendMessage(Component.text("Stand on a node you own.", NamedTextColor.RED));
            return true;
        }
        var result = complex.unmerge(player.getUniqueId(), player.getWorld(), node);
        player.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    /** Re-orients an already-placed building, through the same preview. */
    private boolean rotate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can rotate buildings.", NamedTextColor.RED));
            return true;
        }
        var preview = plugin.getPreviewService();
        if (args.length >= 2 && args[1].equalsIgnoreCase("rotate")) {
            return previewRotate(player, args.length >= 3 ? args[2] : "");
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            if (preview != null) {
                preview.end(player);
            }
            player.sendMessage(Component.text("Rotation cancelled; nothing was charged.",
                    NamedTextColor.YELLOW));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            var session = preview == null ? null : preview.consume(player, args.length >= 3 ? args[2] : "");
            if (session == null
                    || session.kind() != dev.branzx.idle.service.PreviewService.Kind.ROTATE) {
                player.sendMessage(Component.text("That rotation preview is no longer active.",
                        NamedTextColor.YELLOW));
                return true;
            }
            NodeRecord node = nodeStore.getById(session.nodeId());
            if (node == null) {
                player.sendMessage(Component.text("That node no longer exists.", NamedTextColor.RED));
                return true;
            }
            ClaimService.Result result = claimService.rotate(player.getUniqueId(),
                    player.getWorld(), node, session.rotation());
            player.sendMessage(Component.text(result.message(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            return true;
        }

        NodeRecord node = nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4));
        if (node == null || !node.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Stand on a node you own to rotate its building.",
                    NamedTextColor.RED));
            return true;
        }
        if (!node.getType().isProduction()) {
            player.sendMessage(Component.text("Only production nodes have a building.",
                    NamedTextColor.RED));
            return true;
        }
        if (node.isUpgrading()) {
            player.sendMessage(Component.text(
                    "Wait for this node's upgrade to finish before rotating.", NamedTextColor.RED));
            return true;
        }
        if (preview == null || !plugin.getConfig().getBoolean("nodes.preview.enabled", true)) {
            player.sendMessage(Component.text("Placement preview is disabled on this server.",
                    NamedTextColor.RED));
            return true;
        }
        var complex = plugin.getComplexService();
        // A Complex turns as one unit and a long one may only face two ways,
        // so the step skips the orientations its land cannot support.
        if (complex != null && node.isInComplex()) {
            var anchor = complex.anchorOf(node);
            if (anchor != null) {
                node = anchor;
            }
        }
        int step = rotationStep(node);
        int target = node.getRotation() + step;
        // Opens already turned one step, so the first thing shown is a change.
        var session = (complex != null && node.isInComplex())
                ? preview.openRotate(player, node, complex.planRotation(node, target), target)
                : preview.openRotate(player, node,
                        plugin.getSchematicService().getRegistry().definitionFor(node), target);
        player.sendMessage(Component.text("[Idle] Rotation preview for node #" + node.getId()
                + " — cost " + claimService.rotateCost() + ".", NamedTextColor.GOLD));
        player.sendMessage(Component.text("  Facing: " + facing(session.rotation()),
                NamedTextColor.GRAY));
        player.sendMessage(Component.text()
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(CommandLinks.run("[Rotate]", "/idle rotate rotate " + session.token()))
                .append(Component.space())
                .append(CommandLinks.run("[Confirm]", "/idle rotate confirm " + session.token()))
                .append(Component.space())
                .append(CommandLinks.run("[Cancel]", "/idle rotate cancel"))
                .build());
        return true;
    }

    private boolean claimConfirm(Player player, String token) {
        var preview = plugin.getPreviewService();
        var session = preview == null ? null : preview.consume(player, token);
        // A rotate or review token must not be spendable here, or a stale
        // link from another flow could trigger a claim.
        if (session == null
                || session.kind() != dev.branzx.idle.service.PreviewService.Kind.CLAIM) {
            player.sendMessage(Component.text("That placement preview is no longer active. ",
                    NamedTextColor.YELLOW)
                    .append(CommandLinks.suggest("[Claim again]", "/idle claim ")));
            return true;
        }
        // The preview only reserved a ground level; every rule is checked
        // again here because the world may have moved on while it was open.
        ClaimService.Result result = claimService.claim(player.getUniqueId(), player.getWorld(),
                session.chunk(), session.type(), session.originY());
        player.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean claimCancel(Player player) {
        var preview = plugin.getPreviewService();
        if (preview == null || preview.get(player) == null) {
            player.sendMessage(Component.text("You have no placement preview open.",
                    NamedTextColor.YELLOW));
            return true;
        }
        preview.end(player);
        player.sendMessage(Component.text("Placement cancelled; nothing was charged.",
                NamedTextColor.YELLOW));
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
            sender.sendMessage(Component.text("You have no nodes yet. ", NamedTextColor.YELLOW)
                    .append(CommandLinks.run("[Claim Residential]", "/idle claim residential")));
            return true;
        }
        sender.sendMessage(Component.text("=== Your Nodes (" + owned.size() + ") ===", NamedTextColor.YELLOW));
        for (NodeRecord node : owned) {
            String buffer = node.getType().isProduction()
                    ? "  buffer " + node.storageTotal() + "/" + (plugin.getConfig()
                            .getInt("production.buffer-capacity-per-tier", 64) * node.getTier())
                            + (node.bulkStorageTotal() > 0
                                    ? ", bulk " + node.bulkStorageTotal() : "")
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
            sender.sendMessage(Component.text("Hold a worker contract to choose the fuse rarity. ",
                    NamedTextColor.RED)
                    .append(CommandLinks.run("[Open Bag]", "/idle bag")));
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
            sender.sendMessage(Component.text("Stand in a production node you can manage. ",
                    NamedTextColor.RED)
                    .append(CommandLinks.run("[Open Nodes]", "/idle nodes")));
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
            sender.sendMessage(Component.text("Stand in a production node you can manage. ",
                    NamedTextColor.RED)
                    .append(CommandLinks.run("[Open Nodes]", "/idle nodes")));
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
        if (!player.hasPermission("idle.skin")) {
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

    private boolean collect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can collect.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = resolveNode(player, args.length >= 2 ? args[1] : null);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(noTargetNode());
            return true;
        }
        // Helper trust may collect (goes to their inventory for now;
        // routed to the owner's Warehouse in the warehouse phase).
        if (!trustService.canHelp(player.getUniqueId(), node.getOwnerUuid())) {
            sender.sendMessage(Component.text("You are not trusted to collect here.", NamedTextColor.RED));
            return true;
        }
        if (node.getStorage().isEmpty() && node.getBulkStorage().isEmpty()) {
            sender.sendMessage(Component.text("Nothing to collect yet.", NamedTextColor.YELLOW));
            return true;
        }
        // Collect-all routes to the owner's Warehouse, not inventory.
        java.util.Map<String, Integer> movedBreakdown = new java.util.LinkedHashMap<>();
        int collected = warehouseService.collectNode(node, movedBreakdown);
        if (guiManager.gameDesignService() != null) {
            guiManager.gameDesignService().onBufferCollected(node, collected);
        }
        int remaining = node.storageTotal() + node.bulkStorageTotal();
        refreshNodeNpc(node);
        for (String line : dev.branzx.idle.service.TripReport.lines(
                node.getType(), movedBreakdown)) {
            sender.sendMessage(Component.text(line, NamedTextColor.AQUA));
        }
        if (remaining > 0) {
            // Name the pool that is actually blocking: the two fill at very
            // different speeds and the fix differs.
            boolean siloFull = warehouseService.siloFreeSpace(player.getUniqueId()) <= 0;
            boolean vaultFull = warehouseService.freeSpace(player.getUniqueId()) <= 0;
            String which = siloFull && vaultFull ? "Vault และ Silo เต็ม"
                    : siloFull ? "Silo (commons) เต็ม"
                    : vaultFull ? "Vault (ของหายาก) เต็ม" : "ที่เก็บไม่พอ";
            sender.sendMessage(Component.text("เก็บได้ " + collected + " ชิ้น เหลือค้าง "
                    + remaining + " — " + which + ". ", NamedTextColor.YELLOW)
                    .append(CommandLinks.run("[เปิด Warehouse]", "/idle warehouse")));
        } else {
            sender.sendMessage(Component.text("Collected " + collected + " items to Warehouse.",
                    NamedTextColor.GREEN));
        }
        return true;
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
            sender.sendMessage(Component.text(
                    "Usage: /idle commission <slot_1|slot_2|slot_3|catchup> "
                            + "or /idle commission reroll <slot>",
                    NamedTextColor.YELLOW));
            return true;
        }
        var result = args[1].equalsIgnoreCase("reroll") && args.length >= 3
                ? guiManager.gameDesignService().rerollCommission(player.getUniqueId(), args[2])
                : guiManager.gameDesignService().claimCommission(player.getUniqueId(), args[1]);
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
                    dev.branzx.idle.service.PerkService.NO_VISITS);
            perks.setFlag(player.getUniqueId(),
                    dev.branzx.idle.service.PerkService.NO_VISITS, nowClosed);
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
        if (perks.has(target.getUniqueId(), dev.branzx.idle.service.PerkService.NO_VISITS)) {
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
            sender.sendMessage(Component.text("Unknown node type: " + args[1] + " ",
                    NamedTextColor.RED)
                    .append(CommandLinks.run("[Open Map]", "/idle map")));
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

    private boolean frontier(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use Frontier.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !node.getType().isProduction()
                || !player.getUniqueId().equals(node.getOwnerUuid())) {
            player.sendMessage(Component.text("Stand inside your production node.",
                    NamedTextColor.RED));
            return true;
        }
        GameDesignService design = guiManager.gameDesignService();
        if (design == null) return true;
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        if ("info".equals(action)) {
            var profession = design.frontierProfession(player.getUniqueId(), node.getType());
            var equipment = design.frontierEquipment(player.getUniqueId(), node);
            player.sendMessage(Component.text("Frontier "
                    + (design.frontierEnabled(player.getUniqueId()) ? "READY" : "LOCKED"),
                    NamedTextColor.GOLD));
            player.sendMessage(Component.text(profession.id() + " Lv." + profession.level()
                    + " | EXP " + profession.exp() + "/" + profession.nextLevelExp(),
                    NamedTextColor.AQUA));
            if (equipment == null) {
                player.sendMessage(Component.text("Equipment: none", NamedTextColor.GRAY));
            } else {
                player.sendMessage(Component.text("Equipment: " + equipment.id()
                        + " | durability " + equipment.durability() + "/"
                        + equipment.maxDurability(), equipment.active()
                        ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            for (var recipe : design.frontierRecipes(node.getType())) {
                player.sendMessage(Component.text("T" + recipe.tier() + " Lv."
                        + recipe.unlockLevel() + " — " + recipe.materials(),
                        NamedTextColor.GRAY));
            }
            return true;
        }
        GameDesignService.Result result;
        switch (action) {
            case "craft" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("Usage: /idle frontier craft <tier>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                result = design.craftFrontierEquipment(player.getUniqueId(), node,
                        Integer.parseInt(args[2]));
            }
            case "repair" -> result =
                    design.repairFrontierEquipment(player.getUniqueId(), node);
            case "train" -> {
                if (args.length < 4) {
                    player.sendMessage(Component.text(
                            "Usage: /idle frontier train <material> <amount>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                result = design.trainFrontierProfession(player.getUniqueId(), node.getType(),
                        args[2], Integer.parseInt(args[3]));
            }
            default -> {
                player.sendMessage(Component.text(
                        "Usage: /idle frontier [info|craft <tier>|repair|train <material> <amount>]",
                        NamedTextColor.YELLOW));
                return true;
            }
        }
        player.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean explore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can explore.", NamedTextColor.RED));
            return true;
        }
        var exploration = plugin.getExplorationService();
        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "info";
        // Optional trailing node id keeps chat click actions context-free.
        String idArg = switch (action) {
            case "info", "claim" -> args.length >= 3 ? args[2] : null;
            case "prepare", "start" -> args.length >= 4 ? args[3] : null;
            default -> null;
        };
        NodeRecord node = resolveNode(player, idArg);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(noTargetNode());
            return true;
        }

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
                    Component detail = switch (event.getState()) {
                        case "AVAILABLE" -> Component.text("waiting — expires in "
                                        + Math.max(0, (event.getExpiresAt() - now) / 60000) + "m. ",
                                NamedTextColor.YELLOW)
                                .append(CommandLinks.run("[Start]",
                                        "/idle explore start all " + node.getId()));
                        case "RUNNING" -> Component.text("team away — returns in "
                                + Math.max(0, (event.getEndsAt() - now) / 60000) + "m",
                                NamedTextColor.YELLOW);
                        default -> Component.text(event.getGrade() + " result ready! ",
                                        NamedTextColor.YELLOW)
                                .append(CommandLinks.run("[Claim]",
                                        "/idle explore claim " + node.getId()));
                    };
                    sender.sendMessage(Component.text(exploration.eventName(event.getEventType())
                                    + " [" + event.getState() + "] ", NamedTextColor.YELLOW)
                            .append(detail));
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
                int team = Integer.MAX_VALUE;
                if (args.length >= 3 && !"all".equalsIgnoreCase(args[2])) {
                    try {
                        team = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Team must be a number or 'all'.",
                                NamedTextColor.RED));
                        return true;
                    }
                }
                String error = exploration.start(node, team);
                if (error != null) {
                    sender.sendMessage(Component.text(error, NamedTextColor.RED));
                } else {
                    refreshNodeNpc(node);
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
                    refreshNodeNpc(node);
                }
            }
            default -> sender.sendMessage(Component.text(
                    "Usage: /idle explore [info|prepare <speed|quantity|research>"
                            + "|start [team|all]|claim] [nodeId]",
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

    /**
     * Resolves the acted-on node: explicit id first, then the chunk the
     * player stands in, then the Focused Node. Keeps chat click actions
     * usable from anywhere (context-free rule).
     */
    private NodeRecord resolveNode(Player player, String idArg) {
        if (idArg != null) {
            try {
                return nodeStore.getById(Long.parseLong(idArg));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        NodeRecord node = nodeAt(player);
        if (node != null) {
            return node;
        }
        Long focused = guiManager.gameDesignService() == null ? null
                : guiManager.gameDesignService().focusedNode(player.getUniqueId());
        return focused == null ? null : nodeStore.getById(focused);
    }

    private Component noTargetNode() {
        return Component.text("No production node targeted. Stand in one, set a Focused Node, "
                + "or pass a node id. ", NamedTextColor.RED)
                .append(CommandLinks.run("[Open Nodes]", "/idle nodes"));
    }

    /** NPC refresh must use the node's own world; chat clicks can act cross-world. */
    private void refreshNodeNpc(NodeRecord node) {
        var world = plugin.getServer().getWorld(node.getChunk().world());
        if (world != null) {
            npcManager.refreshNode(node, world);
        }
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
                    "Usage: /idle trade <player|accept|decline|offer|view|confirm|cancel> [player]",
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
            case "decline" -> result = tradeService.decline(player);
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
        if (!hasAnyAdminPermission(sender)) {
            sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
            return true;
        }
        return adminCommands.handle(sender, args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<CommandCatalog.Entry> visible = CommandCatalog.playerEntries().stream()
                    .filter(entry -> entry.permission() == null || sender.hasPermission(entry.permission()))
                    .toList();
            List<String> base = new java.util.ArrayList<>(
                    CommandCatalog.suggestions(visible, args[0]));
            if ("help".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                base.add("help");
            }
            if (hasAnyAdminPermission(sender)) {
                if ("admin".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    base.add("admin");
                }
            }
            return base;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            List<CommandCatalog.Entry> visible = CommandCatalog.adminEntries().stream()
                    .filter(entry -> sender.hasPermission(entry.permission()))
                    .toList();
            List<String> result = new java.util.ArrayList<>(
                    CommandCatalog.suggestions(visible, args[1]));
            if ("help".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                result.add("help");
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return CommandCatalog.categories(CommandCatalog.Audience.PLAYER).stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && args[1].equalsIgnoreCase("help")) {
            return CommandCatalog.categories(CommandCatalog.Audience.ADMIN).stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("event")) {
            return List.of("spawn", "cancel", "list");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("pool")) {
            return List.of("mining", "farming", "woodcutting", "livestock", "hunter", "rollback");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("commission")) {
            return List.of("slot_1", "slot_2", "slot_3", "catchup", "reroll");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("commission")
                && args[1].equalsIgnoreCase("reroll")) {
            return List.of("slot_1", "slot_2", "slot_3");
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

    private boolean hasAnyAdminPermission(CommandSender sender) {
        return CommandCatalog.adminEntries().stream()
                .anyMatch(entry -> sender.hasPermission(entry.permission()));
    }

    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }
}
