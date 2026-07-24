package dev.branzx.idle.command;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.schematic.RelPos;
import dev.branzx.idle.schematic.SchematicDefinition;
import dev.branzx.idle.schematic.SchematicRegistry;
import dev.branzx.idle.service.AdminAlerts;
import dev.branzx.idle.service.SchematicService;
import dev.branzx.idle.service.WorkerNpcManager;
import dev.branzx.idle.storage.NodeStore;
import dev.branzx.idle.storage.WorkerStore;
import dev.branzx.idle.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** /idle admin subcommands: schem authoring, npc tools, node inspection. */
public final class AdminCommands {

    private record EditSession(String definitionId, Location origin) {
    }

    private final IdlePlugin plugin;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final SchematicService schematicService;
    private final WorkerNpcManager npcManager;
    private final Map<UUID, EditSession> sessions = new ConcurrentHashMap<>();
    private final dev.branzx.idle.service.DropTableService dropTableService;
    private final dev.branzx.idle.service.AuditService auditService;
    private final dev.branzx.idle.gui.GuiManager guiManager;
    private final dev.branzx.idle.storage.PlayerDataStore dataStore;
    private final dev.branzx.idle.service.ExplorationService explorationService;
    private final dev.branzx.idle.service.CreditService creditService;
    private final dev.branzx.idle.service.ClaimService claimService;

    public AdminCommands(IdlePlugin plugin, NodeStore nodeStore, WorkerStore workerStore,
                         SchematicService schematicService, WorkerNpcManager npcManager,
                         dev.branzx.idle.service.DropTableService dropTableService,
                         dev.branzx.idle.service.AuditService auditService,
                         dev.branzx.idle.gui.GuiManager guiManager,
                         dev.branzx.idle.storage.PlayerDataStore dataStore,
                         dev.branzx.idle.service.ExplorationService explorationService,
                         dev.branzx.idle.service.CreditService creditService,
                         dev.branzx.idle.service.ClaimService claimService) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.schematicService = schematicService;
        this.npcManager = npcManager;
        this.dropTableService = dropTableService;
        this.auditService = auditService;
        this.guiManager = guiManager;
        this.dataStore = dataStore;
        this.explorationService = explorationService;
        this.creditService = creditService;
        this.claimService = claimService;
        guiManager.setAdminTools(this, auditService);
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            return dashboard(sender);
        }
        try {
            return handleInner(sender, args);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid number: " + e.getMessage(), NamedTextColor.RED));
            return true;
        }
    }

    private boolean handleInner(CommandSender sender, String[] args) {
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) {
            return help(sender, args);
        }
        CommandCatalog.Entry entry = CommandCatalog.findAdmin(sub);
        if (entry != null && !sender.hasPermission(entry.permission())) {
            sender.sendMessage(Component.text("คุณไม่มีสิทธิ์ " + entry.permission(),
                    NamedTextColor.RED));
            return true;
        }
        return switch (sub) {
            case "dashboard" -> dashboard(sender);
            case "reload" -> reload(sender, args);
            case "schem" -> schem(sender, args);
            case "npc" -> npc(sender, args);
            case "node" -> nodeInfo(sender);
            case "pool" -> pool(sender, args);
            case "event" -> event(sender, args);
            case "explevel" -> explevel(sender, args);
            case "give" -> give(sender, args);
            case "setcap" -> setcap(sender, args);
            case "skingrant" -> skinGrant(sender, args);
            case "skinreview" -> skinReview(sender, args);
            case "audit" -> audit(sender, args);
            case "credits" -> credits(sender, args);
            case "validate" -> validateContent(sender);
            case "metrics" -> metrics(sender);
            case "claims" -> claims(sender, args);
            case "forceunclaim" -> forceUnclaim(sender, args);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        return help(sender, new String[]{"admin", "help"});
    }

    private boolean dashboard(CommandSender sender) {
        if (sender instanceof Player player) {
            guiManager.openAdminHub(player);
            return true;
        }
        return help(sender, new String[]{"admin", "help"});
    }

    private boolean help(CommandSender sender, String[] args) {
        String filter = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        sender.sendMessage(Component.text("Idle Admin Commands", NamedTextColor.RED));
        if (filter == null) {
            sender.sendMessage(CommandLinks.run("[เปิด Admin Hub]", "/idle admin"));
            Component categories = Component.text("หมวด: ", NamedTextColor.GRAY);
            for (String category : CommandCatalog.categories(CommandCatalog.Audience.ADMIN)) {
                categories = categories.append(CommandLinks.run(
                        "[" + category + "]", "/idle admin help " + category)).append(Component.space());
            }
            sender.sendMessage(categories);
            return true;
        }
        List<CommandCatalog.Entry> entries =
                CommandCatalog.inCategory(CommandCatalog.Audience.ADMIN, filter);
        if (entries.isEmpty()) {
            CommandCatalog.Entry match = CommandCatalog.findAdmin(filter);
            entries = match == null ? List.of() : List.of(match);
        }
        entries = entries.stream()
                .filter(item -> sender.hasPermission(item.permission()))
                .toList();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("ไม่พบ command ในหมวดนี้ หรือคุณไม่มีสิทธิ์",
                    NamedTextColor.RED));
            return true;
        }
        for (CommandCatalog.Entry item : entries) {
            String suffix = item.syntax().isBlank() ? "" : " " + item.syntax();
            String command = "/idle admin " + item.name();
            boolean runsWithoutArguments = item.syntax().isBlank()
                    || item.syntax().startsWith("[");
            Component link = runsWithoutArguments
                    ? CommandLinks.run(command, command)
                    : CommandLinks.suggest(command + suffix, command + " ");
            sender.sendMessage(link
                    .append(Component.text(" — " + item.description(), NamedTextColor.GRAY)));
        }
        return true;
    }

    private boolean validateContent(CommandSender sender) {
        List<String> errors = dropTableService.validate();
        if (errors.isEmpty()) {
            sender.sendMessage(Component.text("Content validation passed: all 5×10 pools are publishable.",
                    NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Content validation failed (" + errors.size() + "):",
                    NamedTextColor.RED));
            errors.stream().limit(20).forEach(error ->
                    sender.sendMessage(Component.text(" - " + error, NamedTextColor.YELLOW)));
        }
        return true;
    }

    private boolean metrics(CommandSender sender) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Long> summary = guiManager.gameDesignService().telemetrySummarySync();
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                sender.sendMessage(Component.text("Idle telemetry — last 7 days",
                        NamedTextColor.AQUA));
                if (summary.isEmpty()) {
                    sender.sendMessage(Component.text("No telemetry recorded yet.", NamedTextColor.GRAY));
                } else {
                    summary.forEach((event, count) -> sender.sendMessage(
                            Component.text(" " + event + ": " + count, NamedTextColor.GRAY)));
                }
            });
        });
        return true;
    }

    private boolean claims(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /idle admin claims <player>", NamedTextColor.YELLOW));
            return true;
        }
        var target = org.bukkit.Bukkit.getOfflinePlayer(args[2]);
        List<NodeRecord> nodes = nodeStore.getByOwner(target.getUniqueId());
        sender.sendMessage(Component.text("Claims for " + args[2] + " (" + nodes.size() + ")",
                NamedTextColor.AQUA));
        nodes.forEach(node -> sender.sendMessage(Component.text(" #" + node.getId() + " "
                + node.getType() + " T" + node.getTier() + " Lv." + node.getExplorationLevel()
                + " @ " + node.getChunk().world() + " " + node.getChunk().x() + ","
                + node.getChunk().z(), NamedTextColor.GRAY)));
        return true;
    }

    private boolean forceUnclaim(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage(Component.text(
                    "Usage: /idle admin forceunclaim <world> <chunkX> <chunkZ> <reason>",
                    NamedTextColor.YELLOW));
            return true;
        }
        var world = org.bukkit.Bukkit.getWorld(args[2]);
        if (world == null) {
            sender.sendMessage(Component.text("Unknown loaded world: " + args[2], NamedTextColor.RED));
            return true;
        }
        int x = Integer.parseInt(args[3]);
        int z = Integer.parseInt(args[4]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
        UUID actor = sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
        String auditId = UUID.randomUUID().toString();
        var result = claimService.forceUnclaim(actor, world,
                new ChunkKey(world.getName(), x, z), reason, auditId);
        sender.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    private boolean credits(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text(
                    "Usage: /idle admin credits <player> <amount> <reason>", NamedTextColor.YELLOW));
            return true;
        }
        org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(args[2]);
        long amount = Long.parseLong(args[3]);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
        String transaction = "ADMIN:" + UUID.randomUUID();
        boolean success = creditService != null && creditService.adjust(target.getUniqueId(), amount,
                "ADMIN_ADJUST", transaction, "{\"reason\":\"" + reason.replace("\"", "'") + "\"}");
        if (success) {
            auditService.logAdmin(actor(sender), transaction, reason, "ADMIN_CREDITS",
                    "target=" + target.getUniqueId() + " amount=" + amount);
        }
        sender.sendMessage(Component.text(success
                        ? "Credits adjusted. Audit transaction: " + transaction
                        : "Credit adjustment failed or transaction was duplicated.",
                success ? NamedTextColor.GREEN : NamedTextColor.RED));
        return true;
    }

    // ---- pool editor ----

    private boolean pool(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        // No path: open the type/bracket browser. Path given: jump straight in.
        if (args.length < 3) {
            new dev.branzx.idle.gui.PoolBrowserMenu(player, guiManager, dropTableService,
                    auditService).open();
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("publish") || action.equals("rollback") || action.equals("discard")) {
            if (args.length < 4) {
                sender.sendMessage(Component.text("Usage: /idle admin pool " + action + " <reason>",
                        NamedTextColor.YELLOW));
                return true;
            }
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
            String auditId = UUID.randomUUID().toString();
            if (action.equals("publish")) {
                var result = dropTableService.publish();
                if (!result.success()) {
                    sender.sendMessage(Component.text("Publish rejected: " + String.join("; ", result.errors()),
                            NamedTextColor.RED));
                    return true;
                }
                auditService.logAdmin(player.getUniqueId(), auditId, reason, "CONTENT_PUBLISH",
                        "drops previousRevision=" + result.revision());
                sender.sendMessage(Component.text("Published drop-table draft | audit " + auditId,
                        NamedTextColor.GREEN));
                return true;
            }
            if (action.equals("discard")) {
                dropTableService.resetDraft();
                auditService.logAdmin(player.getUniqueId(), auditId, reason, "CONTENT_DRAFT_DISCARD",
                        "drops");
                sender.sendMessage(Component.text("Draft reset to published content | audit " + auditId,
                        NamedTextColor.GREEN));
                return true;
            }
            var result = dropTableService.rollback();
            sender.sendMessage(Component.text(result.success()
                            ? "Rolled back to revision " + result.revision() + " | audit " + auditId
                            : result.error(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (result.success()) {
                auditService.logAdmin(player.getUniqueId(), auditId, reason, "CONTENT_ROLLBACK",
                        "drops revision=" + result.revision());
            }
            return true;
        }
        new dev.branzx.idle.gui.PoolEditorMenu(player, guiManager, dropTableService,
                auditService, args[2].toLowerCase(Locale.ROOT)).open();
        return true;
    }

    // ---- exploration events ----

    private boolean event(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "spawn" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin event spawn <type> <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                String type = args[3].toLowerCase(Locale.ROOT);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                String error = explorationService.adminSpawn(node, type);
                String auditId = error == null ? auditAdmin(player.getUniqueId(), "ADMIN_EVENT",
                        reason, "spawn " + type + " @ " + node.getId()) : null;
                sender.sendMessage(Component.text(error == null ? "Event spawned at node #" + node.getId()
                        + " | audit " + auditId
                        : error, error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "cancel" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin event cancel <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                explorationService.cancel(node);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "ADMIN_EVENT",
                        reason, "cancel @ " + node.getId());
                sender.sendMessage(Component.text("Event on node #" + node.getId() + " cancelled.",
                        NamedTextColor.GREEN).append(Component.text(" | audit " + auditId,
                        NamedTextColor.GRAY)));
            }
            case "list" -> {
                var event = explorationService.getEvent(node.getId());
                sender.sendMessage(Component.text("Node #" + node.getId() + " Exploration Lv."
                        + node.getExplorationLevel() + " (bracket " + explorationService.bracket(node) + ")",
                        NamedTextColor.YELLOW));
                sender.sendMessage(Component.text(event == null ? "  No active event"
                        : "  " + explorationService.eventName(event.getEventType()) + " ["
                                + event.getState() + "]", NamedTextColor.WHITE));
                sender.sendMessage(Component.text("  Types: " + String.join(", ",
                        explorationService.eventTypes()), NamedTextColor.GRAY));
            }
            default -> sender.sendMessage(Component.text("Usage: /idle admin event spawn [type] | cancel | list",
                    NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean explevel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /idle admin explevel <level> <reason>",
                    NamedTextColor.YELLOW));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
            return true;
        }
        int level = Integer.parseInt(args[2]);
        explorationService.adminSetLevel(node, level);
        int appliedLevel = node.getExplorationLevel();
        nodeStore.updateProduction(node);
        String auditId = UUID.randomUUID().toString();
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
        sender.sendMessage(Component.text("Node #" + node.getId() + " exploration level = " + appliedLevel
                + " (bracket " + explorationService.bracket(node) + ") | audit " + auditId,
                NamedTextColor.GREEN));
        auditService.logAdmin(player.getUniqueId(), auditId, reason, "ADMIN_EXPLEVEL",
                node.getId() + " requested=" + level + " applied=" + appliedLevel);
        return true;
    }

    // ---- economy ----

    private boolean give(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage(Component.text(
                    "Usage: /idle admin give money <player> <amount> <reason> | "
                            + "give item <player> <material> <count> <reason>",
                    NamedTextColor.YELLOW));
            return true;
        }
        Player target = plugin.getServer().getPlayer(args[3]);
        if (target == null) {
            sender.sendMessage(Component.text("Player must be online: " + args[3], NamedTextColor.RED));
            return true;
        }
        UUID actor = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);

        if (args[2].equalsIgnoreCase("money")) {
            double amount = Double.parseDouble(args[4]);
            var data = dataStore.getOnline(target.getUniqueId());
            if (data == null) {
                sender.sendMessage(Component.text("Target data not loaded.", NamedTextColor.RED));
                return true;
            }
            dataStore.addCoins(target.getUniqueId(), Math.round(amount));
            String auditId = UUID.randomUUID().toString();
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
            auditService.logAdmin(actor, auditId, reason, "ADMIN_GIVE",
                    "money " + amount + " -> " + target.getName());
            sender.sendMessage(Component.text((amount >= 0 ? "Gave " : "Took ") + Math.abs(amount)
                    + " money " + (amount >= 0 ? "to " : "from ") + target.getName()
                    + " | audit " + auditId, NamedTextColor.GREEN));
            return true;
        }
        if (args[2].equalsIgnoreCase("item")) {
            if (args.length < 7) {
                sender.sendMessage(Component.text(
                        "Usage: /idle admin give item <player> <material> <count> <reason>",
                        NamedTextColor.YELLOW));
                return true;
            }
            var material = org.bukkit.Material.matchMaterial(args[4]);
            if (material == null) {
                sender.sendMessage(Component.text("Unknown material: " + args[4], NamedTextColor.RED));
                return true;
            }
            int count = Integer.parseInt(args[5]);
            var leftover = target.getInventory().addItem(new org.bukkit.inventory.ItemStack(material, count));
            for (var overflow : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), overflow);
            }
            String auditId = UUID.randomUUID().toString();
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 6, args.length));
            auditService.logAdmin(actor, auditId, reason, "ADMIN_GIVE",
                    "item " + material + " x" + count + " -> " + target.getName());
            sender.sendMessage(Component.text("Gave " + count + "x " + material + " to " + target.getName()
                    + " | audit " + auditId,
                    NamedTextColor.GREEN));
            return true;
        }
        return usage(sender);
    }

    private boolean setcap(CommandSender sender, String[] args) {
        if (args.length < 6) {
            sender.sendMessage(Component.text("Usage: /idle admin setcap <player> <base> <bonus> <reason>",
                    NamedTextColor.YELLOW));
            return true;
        }
        var target = plugin.getServer().getOfflinePlayer(args[2]);
        int base = Integer.parseInt(args[3]);
        int bonus = Integer.parseInt(args[4]);
        nodeStore.setCap(target.getUniqueId(), base, bonus);
        UUID actor = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        String auditId = UUID.randomUUID().toString();
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
        auditService.logAdmin(actor, auditId, reason, "ADMIN_SETCAP",
                args[2] + " base=" + base + " bonus=" + bonus);
        sender.sendMessage(Component.text("Node cap for " + args[2] + " = " + (base + bonus),
                NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Audit " + auditId, NamedTextColor.GRAY));
        return true;
    }

    /**
     * Grants or revokes a building skin. This is how a contest result is
     * settled: the winning skin ships as a default unlock for everyone, while
     * the winner's exclusive variant is granted here.
     */
    private boolean skinGrant(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage(Component.text(
                    "Usage: /idle admin skingrant <player> <grant|revoke> <skinId> <reason>",
                    NamedTextColor.YELLOW));
            return true;
        }
        var skins = plugin.getSkinRegistry();
        var unlocks = plugin.getPlayerSkinStore();
        if (skins == null || unlocks == null) {
            sender.sendMessage(Component.text("Skins are not available.", NamedTextColor.RED));
            return true;
        }
        boolean revoke = args[3].equalsIgnoreCase("revoke");
        if (!revoke && !args[3].equalsIgnoreCase("grant")) {
            sender.sendMessage(Component.text("Expected 'grant' or 'revoke'.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 6) {
            sender.sendMessage(Component.text("A reason is required.", NamedTextColor.RED));
            return true;
        }
        String skinId = args[4];
        // Granting an id with no skin file would look like it worked and then
        // silently show nothing in the player's menu.
        if (skins.get(skinId) == null) {
            sender.sendMessage(Component.text("No skin '" + skinId + "' is loaded.",
                    NamedTextColor.RED));
            return true;
        }
        var target = plugin.getServer().getOfflinePlayer(args[2]);
        if (revoke) {
            unlocks.revoke(target.getUniqueId(), skinId);
        } else {
            unlocks.grant(target.getUniqueId(), skinId);
        }
        UUID actor = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        String auditId = UUID.randomUUID().toString();
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
        auditService.logAdmin(actor, auditId, reason,
                revoke ? "ADMIN_SKIN_REVOKE" : "ADMIN_SKIN_GRANT",
                args[2] + " skin=" + skinId);
        sender.sendMessage(Component.text((revoke ? "Revoked " : "Granted ") + skinId
                + " for " + args[2], NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Audit " + auditId, NamedTextColor.GRAY));
        return true;
    }

    /**
     * The contest review queue. Automatic validation already rejected
     * block-level abuse; this is where a human judges shape, placement and
     * content, which no rule set can do.
     */
    private boolean skinReview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        var submissions = plugin.getSkinSubmissionService();
        if (submissions == null) {
            sender.sendMessage(Component.text("Skins are not available.", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "list";

        if (action.equals("list")) {
            var pending = submissions.pendingIds();
            if (pending.isEmpty()) {
                sender.sendMessage(Component.text("No submissions are waiting for review.",
                        NamedTextColor.GRAY));
                return true;
            }
            sender.sendMessage(Component.text("[Idle] " + pending.size()
                    + " submission(s) awaiting review:", NamedTextColor.GOLD));
            for (String id : pending) {
                var entry = submissions.loadPending(id);
                var line = Component.text()
                        .append(Component.text("  " + id, NamedTextColor.WHITE));
                if (entry != null && entry.authorName() != null) {
                    line.append(Component.text(" by " + entry.authorName(), NamedTextColor.AQUA));
                }
                if (entry != null) {
                    line.append(Component.text(" ("
                            + (entry.isComplex() ? entry.shape().id() + ", " : "")
                            + entry.blockCount() + " blocks)", NamedTextColor.DARK_GRAY));
                }
                line.append(Component.space())
                        .append(CommandLinks.run("[Show]", "/idle admin skinreview show " + id));
                sender.sendMessage(line.build());
            }
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /idle admin skinreview "
                    + "<list|show|spawn|work|approve|reject> <id> ...", NamedTextColor.YELLOW));
            return true;
        }
        String id = args[3].toLowerCase(Locale.ROOT);
        var pending = submissions.loadPending(id);
        if (pending == null) {
            sender.sendMessage(Component.text("No pending submission '" + id + "'.",
                    NamedTextColor.RED));
            return true;
        }

        switch (action) {
            case "show" -> {
                var preview = plugin.getPreviewService();
                if (preview == null) {
                    sender.sendMessage(Component.text("Preview is unavailable.", NamedTextColor.RED));
                    return true;
                }
                // Drawn as ghost blocks where the reviewer stands: judging a
                // build must never require pasting it into the world.
                ChunkKey here = new ChunkKey(player.getWorld().getName(),
                        player.getLocation().getBlockX() >> 4,
                        player.getLocation().getBlockZ() >> 4);
                var session = preview.openReview(player, reviewParts(pending, here), here,
                        player.getLocation().getBlockY());
                var bounds = pending.primary() == null
                        ? new dev.branzx.idle.schematic.SchematicDefinition.Bounds(0, 0, 0, 0, 0, 0)
                        : pending.primary().bounds();
                sender.sendMessage(Component.text("[Idle] Reviewing '" + id + "' by "
                        + pending.authorName(), NamedTextColor.GOLD));
                sender.sendMessage(Component.text("  "
                        + (pending.isComplex()
                                ? pending.shape().id() + " Complex, " + pending.pieces().size()
                                        + " pieces, "
                                : bounds.width() + "×" + bounds.depth() + "×" + bounds.height() + ", ")
                        + pending.blockCount()
                        + " blocks, " + pending.spawnAnchors().size()
                        + " spawn / " + pending.workAnchors().size()
                        + " work anchors", NamedTextColor.GRAY));
                if (pending.spawnAnchors().isEmpty()) {
                    sender.sendMessage(Component.text(
                            "  No spawn anchors — workers would fall back to a line in front, "
                                    + "which can place an NPC inside a wall.", NamedTextColor.RED));
                }
                sender.sendMessage(Component.text()
                        .append(Component.text("  ", NamedTextColor.GRAY))
                        .append(CommandLinks.run("[Rotate]",
                                "/idle claim rotate " + session.token()))
                        .append(Component.space())
                        .append(CommandLinks.suggest("[Approve]",
                                "/idle admin skinreview approve " + id + " "))
                        .append(Component.space())
                        .append(CommandLinks.suggest("[Reject]",
                                "/idle admin skinreview reject " + id + " "))
                        .build());
            }
            case "spawn", "work" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text("Usage: /idle admin skinreview "
                            + action + " <id> <slot>", NamedTextColor.YELLOW));
                    return true;
                }
                var preview = plugin.getPreviewService();
                var session = preview == null ? null : preview.get(player);
                // Anchors are relative to the build's origin, so the ghost has
                // to be on screen for "where I am standing" to mean anything.
                if (session == null
                        || session.kind() != dev.branzx.idle.service.PreviewService.Kind.REVIEW) {
                    sender.sendMessage(Component.text("Run ", NamedTextColor.RED)
                            .append(CommandLinks.run("[Show]",
                                    "/idle admin skinreview show " + id))
                            .append(Component.text(" first, then stand where the worker should be.",
                                    NamedTextColor.RED)));
                    return true;
                }
                int slot = Integer.parseInt(args[4]);
                if (slot < 1 || slot > 5) {
                    sender.sendMessage(Component.text("Slot must be 1-5.", NamedTextColor.RED));
                    return true;
                }
                var origin = session.origin(player.getWorld());
                var pos = new dev.branzx.idle.schematic.RelPos(
                        player.getLocation().getBlockX() - origin.getBlockX(),
                        player.getLocation().getBlockY() - origin.getBlockY(),
                        player.getLocation().getBlockZ() - origin.getBlockZ());
                SchematicDefinition.setSlot(action.equals("work")
                                ? pending.workAnchors()
                                : pending.spawnAnchors(),
                        slot - 1, pos);
                if (!submissions.saveAnchors(pending)) {
                    sender.sendMessage(Component.text("Could not save the anchor.",
                            NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text("  " + action + " slot " + slot + " = "
                        + pos.x() + "," + pos.y() + "," + pos.z(), NamedTextColor.GREEN));
            }
            case "approve" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin skinreview approve <id> <reason>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                if (!submissions.approve(pending)) {
                    sender.sendMessage(Component.text("Approval failed; check the server log.",
                            NamedTextColor.RED));
                    return true;
                }
                String auditId = auditAdmin(player.getUniqueId(), "SKIN_APPROVE", reason,
                        "id=" + id + " author=" + pending.author());
                sender.sendMessage(Component.text("Approved '" + id
                        + "'. It is now available to every player. | audit " + auditId,
                        NamedTextColor.GREEN));
                notifyAuthor(pending, Component.text("[Idle] Your build '" + id
                        + "' was approved! It is now a skin everyone can use, credited to you.",
                        NamedTextColor.GREEN));
            }
            case "reject" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin skinreview reject <id> <reason>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                submissions.reject(id);
                String auditId = auditAdmin(player.getUniqueId(), "SKIN_REJECT", reason,
                        "id=" + id + " author=" + pending.author());
                sender.sendMessage(Component.text("Rejected '" + id + "'. | audit " + auditId,
                        NamedTextColor.YELLOW));
                // The author is told why, so a resubmission can actually fix it.
                notifyAuthor(pending, Component.text("[Idle] Your build '" + id
                        + "' was not accepted: " + reason, NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Unknown review action '" + action + "'.",
                    NamedTextColor.RED));
        }
        return true;
    }

    /**
     * Lays a submission's pieces out around the reviewer's chunk, in the same
     * relative arrangement they would take on a real Complex.
     */
    private java.util.List<dev.branzx.idle.service.PreviewService.Part> reviewParts(
            dev.branzx.idle.skin.SkinSubmissionService.Pending pending, ChunkKey here) {
        java.util.List<dev.branzx.idle.service.PreviewService.Part> parts = new java.util.ArrayList<>();
        for (var entry : pending.pieces().entrySet()) {
            String[] cell = entry.getKey().split(",");
            int col = 0;
            int row = 0;
            if (cell.length == 2) {
                try {
                    col = Integer.parseInt(cell[0].trim());
                    row = Integer.parseInt(cell[1].trim());
                } catch (NumberFormatException ignored) {
                    // Malformed cell id; draw it on the reviewer's own chunk.
                }
            }
            parts.add(new dev.branzx.idle.service.PreviewService.Part(
                    new ChunkKey(here.world(), here.x() + col, here.z() + row), entry.getValue()));
        }
        return parts;
    }

    private void notifyAuthor(dev.branzx.idle.skin.SkinSubmissionService.Pending pending,
                              Component message) {
        if (pending.author() == null) {
            return;
        }
        var author = plugin.getServer().getPlayer(pending.author());
        if (author != null && author.isOnline()) {
            author.sendMessage(message);
        }
    }

    // ---- audit browser ----

    private boolean audit(CommandSender sender, String[] args) {
        UUID filter = null;
        if (args.length >= 3) {
            filter = plugin.getServer().getOfflinePlayer(args[2]).getUniqueId();
        }
        UUID finalFilter = filter;
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                var entries = auditService.recentSync(finalFilter, 15);
                sender.sendMessage(Component.text("=== Audit (latest " + entries.size() + ") ===",
                        NamedTextColor.YELLOW));
                for (var entry : entries) {
                    String name = entry.actor();
                    try {
                        var offline = plugin.getServer().getOfflinePlayer(UUID.fromString(entry.actor()));
                        if (offline.getName() != null) {
                            name = offline.getName();
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                    sender.sendMessage(Component.text(
                            entry.createdAt() + " " + name + " " + entry.action() + " " + entry.detail(),
                            NamedTextColor.WHITE));
                }
            }
        }.runTaskAsynchronously(plugin);
        return true;
    }

    private boolean reload(CommandSender sender) {
        sender.sendMessage(Component.text(
                "Reload requires an audit reason: /idle admin reload <reason>", NamedTextColor.YELLOW));
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length < 3) return reload(sender);
        String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        plugin.reloadConfig();
        schematicService.getRegistry().loadAll();
        // Approving a contest entry drops a skin file; reload must pick it up
        // without a restart, same as schematics.
        if (plugin.getSkinRegistry() != null) {
            plugin.getSkinRegistry().loadAll();
        }
        if (dropTableService != null) {
            dropTableService.load();
            List<String> errors = dropTableService.validate();
            if (!errors.isEmpty()) {
                AdminAlerts.broadcast("idle.admin.content",
                        Component.text()
                                .append(Component.text("[Alert] ", NamedTextColor.RED))
                                .append(Component.text("Content validation failed after reload ("
                                        + errors.size() + " issue(s)). ", NamedTextColor.YELLOW))
                                .append(CommandLinks.run("[Validate]", "/idle admin validate"))
                                .build());
            }
        }
        String auditId = auditAdmin(actor(sender), "ADMIN_RELOAD", reason,
                "config,published pools,schematics,skins");
        sender.sendMessage(Component.text("Idle config, pools, schematics + skins reloaded.",
                NamedTextColor.GREEN).append(Component.text(" | audit " + auditId, NamedTextColor.GRAY)));
        return true;
    }

    // ---- schem authoring ----

    private boolean schem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 3) {
            return usage(sender);
        }
        SchematicRegistry registry = schematicService.getRegistry();
        String action = args[2].toLowerCase(Locale.ROOT);

        if (action.equals("edit")) {
            if (args.length < 5) {
                sender.sendMessage(Component.text("Usage: /idle admin schem edit <id>  (ids: "
                        + String.join(", ", registry.ids()) + ")", NamedTextColor.YELLOW));
                return true;
            }
            NodeRecord node = nodeAt(player);
            if (node == null || !node.getType().isProduction()) {
                sender.sendMessage(Component.text("Stand in a production node to anchor the edit session.",
                        NamedTextColor.RED));
                return true;
            }
            String id = args[3].toLowerCase(Locale.ROOT);
            if (registry.get(id) == null) {
                registry.put(new SchematicDefinition(id));
            }
            sessions.put(player.getUniqueId(),
                    new EditSession(id, schematicService.origin(node, player.getWorld())));
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
            String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_DRAFT_BEGIN", reason,
                    "id=" + id + " node=" + node.getId());
            sender.sendMessage(Component.text("Editing schematic '" + id + "' anchored to this node. "
                    + "Walk around and use setspawn/setwork/setanim, then save. | audit " + auditId,
                    NamedTextColor.GREEN));
            return true;
        }

        if (action.equals("capture")) {
            if (args.length < 7) {
                sender.sendMessage(Component.text(
                        "Usage: /idle admin schem capture <id> [baseY] [height] — captures the WHOLE chunk "
                                + "you stand in, from baseY (default: your feet) up <height> blocks "
                                + "(default 16). Buildings are designed chunk-sized: 1 node = 1 chunk.",
                        NamedTextColor.YELLOW));
                return true;
            }
            String id = args[3].toLowerCase(Locale.ROOT);
            int baseY = Integer.parseInt(args[4]);
            int height = Integer.parseInt(args[5]);
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 6, args.length));
            SchematicDefinition definition = registry.get(id);
            if (definition == null) {
                definition = new SchematicDefinition(id);
                registry.put(definition);
            }
            definition.getBlocks().clear();
            // Origin mirrors the paste origin: chunk center column at base Y.
            int chunkX = player.getLocation().getBlockX() >> 4;
            int chunkZ = player.getLocation().getBlockZ() >> 4;
            int centerX = (chunkX << 4) + 8;
            int centerZ = (chunkZ << 4) + 8;

            // Scan the whole box first, then keep air only within `padding`
            // blocks of the structure — pasting won't flatten terrain that is
            // far from the building, but interiors/doorways still get carved.
            int padding = plugin.getConfig().getInt("schematics-capture.air-padding", 2);
            String[][][] data = new String[16][height][16];
            boolean[][][] solid = new boolean[16][height][16];
            for (int dx = -8; dx <= 7; dx++) {
                for (int dy = 0; dy < height; dy++) {
                    for (int dz = -8; dz <= 7; dz++) {
                        var block = player.getWorld().getBlockAt(centerX + dx, baseY + dy, centerZ + dz);
                        data[dx + 8][dy][dz + 8] = block.getBlockData().getAsString();
                        solid[dx + 8][dy][dz + 8] = !block.getType().isAir();
                    }
                }
            }
            int kept = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < 16; z++) {
                        boolean keep = solid[x][y][z] || nearSolid(solid, x, y, z, height, padding);
                        if (keep) {
                            definition.getBlocks().add((x - 8) + "," + y + "," + (z - 8) + "|" + data[x][y][z]);
                            kept++;
                        }
                    }
                }
            }
            registry.save(definition);
            // Anchor an edit session at the capture origin so setspawn/setwork
            // can be authored right here in the build chunk.
            sessions.put(player.getUniqueId(), new EditSession(id,
                    new Location(player.getWorld(), centerX, baseY, centerZ)));
            String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_CAPTURE", reason,
                    "id=" + id + " chunk=" + chunkX + "," + chunkZ + " baseY=" + baseY
                            + " height=" + height + " blocks=" + kept);
            sender.sendMessage(Component.text("Captured chunk " + chunkX + "," + chunkZ + " (baseY=" + baseY
                    + ", h=" + height + ", " + kept + " blocks kept, far-air skipped) into '" + id
                    + "'. Edit session started — walk to each bed and /idle admin schem setspawn <slot>, "
                    + "then setwork/setanim/save. | audit " + auditId, NamedTextColor.GREEN));
            return true;
        }

        if (action.equals("rebuild")) {
            if (args.length < 4) {
                sender.sendMessage(Component.text(
                        "Usage: /idle admin schem rebuild <reason>", NamedTextColor.YELLOW));
                return true;
            }
            NodeRecord node = nodeAt(player);
            if (node == null || !node.getType().isProduction()) {
                sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
                return true;
            }
            schematicService.rebuild(node, player.getWorld());
            String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_REBUILD", reason,
                    "node=" + node.getId());
            sender.sendMessage(Component.text("Building re-pasted (terrain snapshot untouched).",
                    NamedTextColor.GREEN).append(Component.text(" | audit " + auditId,
                    NamedTextColor.GRAY)));
            return true;
        }

        EditSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            sender.sendMessage(Component.text("Start with /idle admin schem edit <id>.", NamedTextColor.RED));
            return true;
        }
        SchematicDefinition definition = registry.get(session.definitionId());

        switch (action) {
            case "setspawn" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin schem setspawn <slot> <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                int slot = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
                RelPos rel = relFeet(player, session);
                SchematicDefinition.setSlot(definition.getSpawnAnchors(), slot - 1, rel);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_DRAFT_EDIT", reason,
                        definition.getId() + " spawn." + slot + "=" + rel.serialize());
                sender.sendMessage(Component.text("Spawn anchor " + slot + " = " + rel.serialize(),
                        NamedTextColor.GREEN).append(Component.text(" | audit " + auditId,
                        NamedTextColor.GRAY)));
            }
            case "setwork" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin schem setwork <slot> <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                int slot = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
                RelPos rel = relFeet(player, session);
                SchematicDefinition.setSlot(definition.getWorkAnchors(), slot - 1, rel);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_DRAFT_EDIT", reason,
                        definition.getId() + " work." + slot + "=" + rel.serialize());
                sender.sendMessage(Component.text("Work anchor " + slot + " = " + rel.serialize(),
                        NamedTextColor.GREEN).append(Component.text(" | audit " + auditId,
                        NamedTextColor.GRAY)));
            }
            case "setwander" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin schem setwander <radius> <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                int radius = args.length >= 4 ? Integer.parseInt(args[3]) : 5;
                definition.setWanderRadius(radius);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_DRAFT_EDIT", reason,
                        definition.getId() + " wander=" + radius);
                sender.sendMessage(Component.text("Wander radius = " + radius + " | audit " + auditId,
                        NamedTextColor.GREEN));
            }
            case "setanim" -> {
                if (args.length < 6) {
                    sender.sendMessage(Component.text("Usage: /idle admin schem setanim <state> <profile>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String state = args[3].toUpperCase(Locale.ROOT);
                definition.getProfiles().put(state, args[4]);
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_DRAFT_EDIT", reason,
                        definition.getId() + " profile." + state + "=" + args[4]);
                sender.sendMessage(Component.text(state + " -> profile '" + args[4]
                        + "' | audit " + auditId, NamedTextColor.GREEN));
            }
            case "save" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin schem save <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                registry.save(definition);
                sessions.remove(player.getUniqueId());
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "SCHEMATIC_SAVE", reason,
                        "id=" + definition.getId());
                sender.sendMessage(Component.text("Schematic '" + definition.getId()
                        + "' saved. Nodes pick it up on next NPC refresh. | audit " + auditId,
                        NamedTextColor.GREEN));
            }
            default -> usage(sender);
        }
        return true;
    }

    /** True if any solid block lies within chebyshev distance {@code r}. */
    private boolean nearSolid(boolean[][][] solid, int x, int y, int z, int height, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (nx >= 0 && nx < 16 && ny >= 0 && ny < height && nz >= 0 && nz < 16
                            && solid[nx][ny][nz]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private RelPos relFeet(Player player, EditSession session) {
        Location feet = player.getLocation();
        Location origin = session.origin();
        return new RelPos(feet.getBlockX() - origin.getBlockX(),
                feet.getBlockY() - origin.getBlockY(),
                feet.getBlockZ() - origin.getBlockZ());
    }

    // ---- npc tools ----

    private boolean npc(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null || !node.getType().isProduction()) {
            sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
            return true;
        }
        String action = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "refresh" -> {
                if (args.length < 4) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin npc refresh <reason>", NamedTextColor.YELLOW));
                    return true;
                }
                npcManager.refreshNode(node, player.getWorld());
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                String auditId = auditAdmin(player.getUniqueId(), "ADMIN_NPC_REFRESH", reason,
                        "node=" + node.getId());
                sender.sendMessage(Component.text("NPCs respawned. | audit " + auditId,
                        NamedTextColor.GREEN));
            }
            case "list" -> {
                List<String> lines = npcManager.describeNode(node.getId());
                sender.sendMessage(Component.text("NPCs (" + lines.size() + "):", NamedTextColor.YELLOW));
                for (String line : lines) {
                    sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE));
                }
            }
            case "state" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text(
                            "Usage: /idle admin npc state <state|clear> <reason>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String state = args.length >= 4 ? args[3] : "clear";
                String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
                if (state.equalsIgnoreCase("clear")) {
                    npcManager.setStateOverride(node.getId(), null);
                    sender.sendMessage(Component.text("State override cleared.", NamedTextColor.GREEN));
                } else {
                    npcManager.setStateOverride(node.getId(), state);
                    sender.sendMessage(Component.text("Previewing state " + state.toUpperCase(Locale.ROOT)
                            + " (clear with /idle admin npc state clear).", NamedTextColor.GREEN));
                }
                String auditId = auditAdmin(player.getUniqueId(), "ADMIN_NPC_STATE", reason,
                        "node=" + node.getId() + " state=" + state);
                sender.sendMessage(Component.text("Audit " + auditId, NamedTextColor.GRAY));
            }
            default -> usage(sender);
        }
        return true;
    }

    // ---- node info ----

    private boolean nodeInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("In-game only.", NamedTextColor.RED));
            return true;
        }
        NodeRecord node = nodeAt(player);
        if (node == null) {
            sender.sendMessage(Component.text("This chunk is unclaimed.", NamedTextColor.YELLOW));
            return true;
        }
        sender.sendMessage(Component.text("Node #" + node.getId() + " " + node.getType()
                + " T" + node.getTier() + " state=" + node.getState()
                + " originY=" + node.getOriginY(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Owner: " + node.getOwnerUuid(), NamedTextColor.GRAY));
        for (WorkerRecord worker : workerStore.getAssigned(node.getId())) {
            sender.sendMessage(Component.text("  " + worker.getName() + " [" + worker.getRarity()
                    + "] " + worker.getState() + " Lv." + worker.getLevel()
                    + " " + worker.getStats().serialize(), NamedTextColor.WHITE));
        }
        return true;
    }

    private NodeRecord nodeAt(Player player) {
        return nodeStore.getByChunk(new ChunkKey(player.getWorld().getName(),
                player.getLocation().getBlockX() >> 4,
                player.getLocation().getBlockZ() >> 4));
    }

    private UUID actor(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : new UUID(0, 0);
    }

    private String auditAdmin(UUID actor, String action, String reason, String detail) {
        String auditId = UUID.randomUUID().toString();
        auditService.logAdmin(actor, auditId, reason, action, detail);
        return auditId;
    }
}
