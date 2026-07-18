package dev.branzx.idlefarm.command;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.schematic.RelPos;
import dev.branzx.idlefarm.schematic.SchematicDefinition;
import dev.branzx.idlefarm.schematic.SchematicRegistry;
import dev.branzx.idlefarm.service.SchematicService;
import dev.branzx.idlefarm.service.WorkerNpcManager;
import dev.branzx.idlefarm.storage.NodeStore;
import dev.branzx.idlefarm.storage.WorkerStore;
import dev.branzx.idlefarm.worker.WorkerRecord;
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

    private final IdleFarmPlugin plugin;
    private final NodeStore nodeStore;
    private final WorkerStore workerStore;
    private final SchematicService schematicService;
    private final WorkerNpcManager npcManager;
    private final Map<UUID, EditSession> sessions = new ConcurrentHashMap<>();

    public AdminCommands(IdleFarmPlugin plugin, NodeStore nodeStore, WorkerStore workerStore,
                         SchematicService schematicService, WorkerNpcManager npcManager) {
        this.plugin = plugin;
        this.nodeStore = nodeStore;
        this.workerStore = workerStore;
        this.schematicService = schematicService;
        this.npcManager = npcManager;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            return usage(sender);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> reload(sender);
            case "schem" -> schem(sender, args);
            case "npc" -> npc(sender, args);
            case "node" -> nodeInfo(sender);
            default -> usage(sender);
        };
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(Component.text("""
                /idle admin reload
                /idle admin schem edit <id> | setspawn <slot> | setwork | setwander <r> | setanim <state> <profile> | save | rebuild
                /idle admin npc refresh | list | state <state|clear>
                /idle admin node""", NamedTextColor.YELLOW));
        return true;
    }

    private boolean reload(CommandSender sender) {
        plugin.reloadConfig();
        schematicService.getRegistry().loadAll();
        sender.sendMessage(Component.text("IdleFarm config + schematics reloaded.", NamedTextColor.GREEN));
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
            if (args.length < 4) {
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
            sender.sendMessage(Component.text("Editing schematic '" + id + "' anchored to this node. "
                    + "Walk around and use setspawn/setwork/setanim, then save.", NamedTextColor.GREEN));
            return true;
        }

        if (action.equals("rebuild")) {
            NodeRecord node = nodeAt(player);
            if (node == null || !node.getType().isProduction()) {
                sender.sendMessage(Component.text("Stand in a production node.", NamedTextColor.RED));
                return true;
            }
            schematicService.rebuild(node, player.getWorld());
            sender.sendMessage(Component.text("Building re-pasted (terrain snapshot untouched).",
                    NamedTextColor.GREEN));
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
                int slot = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
                RelPos rel = relFeet(player, session);
                List<RelPos> anchors = definition.getSpawnAnchors();
                while (anchors.size() < slot) {
                    anchors.add(rel);
                }
                anchors.set(slot - 1, rel);
                sender.sendMessage(Component.text("Spawn anchor " + slot + " = " + rel.serialize(),
                        NamedTextColor.GREEN));
            }
            case "setwork" -> {
                RelPos rel = relFeet(player, session);
                definition.getWorkAnchors().add(rel);
                sender.sendMessage(Component.text("Work anchor #" + definition.getWorkAnchors().size()
                        + " = " + rel.serialize(), NamedTextColor.GREEN));
            }
            case "setwander" -> {
                int radius = args.length >= 4 ? Integer.parseInt(args[3]) : 5;
                definition.setWanderRadius(radius);
                sender.sendMessage(Component.text("Wander radius = " + radius, NamedTextColor.GREEN));
            }
            case "setanim" -> {
                if (args.length < 5) {
                    sender.sendMessage(Component.text("Usage: /idle admin schem setanim <state> <profile>",
                            NamedTextColor.YELLOW));
                    return true;
                }
                String state = args[3].toUpperCase(Locale.ROOT);
                definition.getProfiles().put(state, args[4]);
                sender.sendMessage(Component.text(state + " -> profile '" + args[4] + "'", NamedTextColor.GREEN));
            }
            case "save" -> {
                registry.save(definition);
                sessions.remove(player.getUniqueId());
                sender.sendMessage(Component.text("Schematic '" + definition.getId()
                        + "' saved. Nodes pick it up on next NPC refresh.", NamedTextColor.GREEN));
            }
            default -> usage(sender);
        }
        return true;
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
                npcManager.refreshNode(node, player.getWorld());
                sender.sendMessage(Component.text("NPCs respawned.", NamedTextColor.GREEN));
            }
            case "list" -> {
                List<String> lines = npcManager.describeNode(node.getId());
                sender.sendMessage(Component.text("NPCs (" + lines.size() + "):", NamedTextColor.YELLOW));
                for (String line : lines) {
                    sender.sendMessage(Component.text("  " + line, NamedTextColor.WHITE));
                }
            }
            case "state" -> {
                String state = args.length >= 4 ? args[3] : "clear";
                if (state.equalsIgnoreCase("clear")) {
                    npcManager.setStateOverride(node.getId(), null);
                    sender.sendMessage(Component.text("State override cleared.", NamedTextColor.GREEN));
                } else {
                    npcManager.setStateOverride(node.getId(), state);
                    sender.sendMessage(Component.text("Previewing state " + state.toUpperCase(Locale.ROOT)
                            + " (clear with /idle admin npc state clear).", NamedTextColor.GREEN));
                }
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
}
