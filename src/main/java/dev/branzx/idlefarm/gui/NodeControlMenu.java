package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.ExplorationService;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Redesigned production-node workbench. Frequent actions share one row,
 * progression shares another, and navigation is fixed on the bottom row.
 */
public final class NodeControlMenu extends Menu {

    private final GuiManager gui;
    private final long nodeId;

    public NodeControlMenu(Player viewer, GuiManager gui, long nodeId) {
        super(viewer);
        this.gui = gui;
        this.nodeId = nodeId;
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        NodeRecord node = node();
        return Component.text(node == null ? "Node Control"
                        : "Node Control | " + Ui.pretty(node.getType().name()),
                NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        NodeRecord node = node();
        if (node == null) {
            viewer.closeInventory();
            return;
        }
        fill();

        List<WorkerRecord> crew = gui.workerStore().getAssigned(node.getId());
        set(4, summary(node, crew));
        drawCrew(node, crew);

        // Primary actions: every visit uses the same three positions.
        set(20, collectIcon(node), event -> collect(node));
        ExplorationService.EventRecord exploration =
                gui.explorationService().getEvent(node.getId());
        set(22, explorationIcon(node, exploration),
                event -> new ExplorationMenu(viewer, gui, nodeId).open());
        drawUpgrade(node);

        // Progression and infrequent configuration.
        drawFocus(node);
        drawBuild(node);
        set(33, convertIcon(node),
                event -> new ConvertTypeMenu(viewer, gui, node).open());

        // Fixed navigation row.
        set(36, Icon.of(Material.FILLED_MAP)
                .name("Territory Map", NamedTextColor.GREEN)
                .lore("View nearby chunks and claims", NamedTextColor.GRAY)
                .build(), event -> gui.openTerritoryMap(viewer));
        set(40, Icon.of(Material.ARROW)
                .name("All Nodes", NamedTextColor.GREEN)
                .lore("Return to the Node list", NamedTextColor.GRAY)
                .build(), event -> gui.openNodes(viewer));
        set(44, unclaimIcon(node), event -> confirmUnclaim(node));
    }

    private NodeRecord node() {
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(node -> node.getId() == nodeId)
                .findFirst().orElse(null);
    }

    private ItemStack summary(NodeRecord node, List<WorkerRecord> crew) {
        List<Component> lore = new ArrayList<>();
        lore.add(Ui.line("Chunk " + node.getChunk().x() + ", " + node.getChunk().z(),
                NamedTextColor.GRAY));
        lore.add(Ui.line("Tier " + node.getTier() + " | " + crew.size() + "/"
                + node.getTier() + " workers", NamedTextColor.AQUA));
        if ("STORAGE_FULL".equals(node.getState())) {
            lore.add(Ui.status("STOPPED | BUFFER FULL", NamedTextColor.RED));
        } else if (crew.isEmpty()) {
            lore.add(Ui.status("IDLE | ASSIGN A WORKER", NamedTextColor.YELLOW));
        } else {
            lore.add(Ui.status("ACTIVE | PRODUCING", NamedTextColor.GREEN));
        }
        return Icon.of(Material.LECTERN)
                .name(Ui.pretty(node.getType().name()) + " Node", NamedTextColor.GREEN)
                .loreComponents(lore).build();
    }

    private void drawCrew(NodeRecord node, List<WorkerRecord> crew) {
        int first = 13 - ((node.getTier() - 1) / 2);
        for (int index = 0; index < node.getTier() && index < 9; index++) {
            int slot = first + index;
            if (index < crew.size()) {
                WorkerRecord worker = crew.get(index);
                set(slot, workerIcon(worker), event ->
                        new WorkerDetailMenu(viewer, gui, worker.getWorkerUuid(),
                                "Back to Node",
                                () -> new NodeControlMenu(viewer, gui, nodeId).open()).open());
            } else {
                int displaySlot = index + 1;
                set(slot, Icon.of(Material.ITEM_FRAME)
                        .name("Worker Slot " + displaySlot, NamedTextColor.YELLOW)
                        .loreComponents(List.of(
                                Ui.line("Empty", NamedTextColor.GRAY),
                                Ui.click("choose a Worker")))
                        .build(), event -> openAssignPicker(node));
            }
        }
    }

    private ItemStack collectIcon(NodeRecord node) {
        int capacity = bufferCapacity(node);
        List<Component> lore = new ArrayList<>();
        lore.add(Ui.bar("Buffer", capacity == 0 ? 0
                        : node.storageTotal() / (double) capacity,
                node.storageTotal() >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                node.storageTotal() + "/" + capacity));
        node.getStorage().entrySet().stream().limit(5).forEach(entry ->
                lore.add(Ui.line("- " + Ui.pretty(entry.getKey()) + " x" + entry.getValue(),
                        NamedTextColor.WHITE)));
        if (node.getStorage().size() > 5) {
            lore.add(Ui.line("...and " + (node.getStorage().size() - 5) + " more",
                    NamedTextColor.DARK_GRAY));
        }
        int bulkTotal = node.bulkStorageTotal();
        int bulkCapacity = gui.productionEngine() == null ? 0
                : gui.productionEngine().currentBulkCapacity(node);
        if (bulkCapacity > 0) {
            lore.add(Ui.bar("Bulk", bulkTotal / (double) bulkCapacity,
                    bulkTotal >= bulkCapacity ? NamedTextColor.RED : NamedTextColor.AQUA,
                    bulkTotal + "/" + bulkCapacity));
        } else if (bulkTotal > 0) {
            // Lane inactive (no crew / disabled): capacity is undefined, so
            // show the raw count rather than a 0/0 bar.
            lore.add(Ui.line("Bulk commons: " + bulkTotal, NamedTextColor.AQUA));
        }
        boolean empty = node.getStorage().isEmpty() && node.getBulkStorage().isEmpty();
        lore.add(empty
                ? Ui.status("EMPTY", NamedTextColor.DARK_GRAY)
                : Ui.click("collect to Warehouse"));
        return Icon.of(Material.HOPPER)
                .name("Collect", empty ? NamedTextColor.GRAY : NamedTextColor.GOLD)
                .loreComponents(lore).build();
    }

    private void collect(NodeRecord node) {
        if (node.getStorage().isEmpty() && node.getBulkStorage().isEmpty()) {
            viewer.sendMessage(Component.text("This Node buffer is empty.",
                    NamedTextColor.YELLOW));
            return;
        }
        java.util.Map<String, Integer> movedBreakdown = new java.util.LinkedHashMap<>();
        int moved = gui.warehouseService().collectNode(node, movedBreakdown);
        if (gui.gameDesignService() != null) {
            gui.gameDesignService().onBufferCollected(node, moved);
        }
        gui.npcManager().refreshNode(node, viewer.getWorld());
        for (String line : dev.branzx.idlefarm.service.TripReport.lines(
                node.getType(), movedBreakdown)) {
            viewer.sendMessage(Component.text(line, NamedTextColor.AQUA));
        }
        viewer.sendMessage(Component.text("Collected " + moved + " items to Warehouse.",
                moved > 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        redraw();
    }

    private ItemStack explorationIcon(NodeRecord node,
                                      ExplorationService.EventRecord event) {
        ExplorationService service = gui.explorationService();
        long needed = service.expForNextExplorationLevel(node.getExplorationLevel());
        List<Component> lore = new ArrayList<>();
        lore.add(Ui.line("Bracket " + service.bracket(node), NamedTextColor.LIGHT_PURPLE));
        lore.add(Ui.bar("Lv." + node.getExplorationLevel(),
                needed <= 0 ? 1.0 : node.getExplorationExp() / (double) needed,
                NamedTextColor.LIGHT_PURPLE,
                Ui.num(node.getExplorationExp()) + "/" + Ui.num(needed)));
        if (event == null) {
            lore.add(Ui.status("NO EVENT", NamedTextColor.DARK_GRAY));
        } else {
            lore.add(Ui.line(service.eventName(event.getEventType()), NamedTextColor.GOLD));
            switch (event.getState()) {
                case "AVAILABLE" -> {
                    var preview = service.preview(node, Integer.MAX_VALUE);
                    lore.add(Ui.line(preview.workers() + " workers | "
                            + Ui.time(preview.durationMillis()), NamedTextColor.AQUA));
                    lore.add(Ui.line(String.format("Great %.1f%% | Jackpot %.1f%%",
                            preview.greatChance(), preview.jackpotChance()),
                            NamedTextColor.GOLD));
                    lore.add(Ui.click("send expedition"));
                }
                case "RUNNING" -> lore.add(Ui.status("RETURNS IN "
                                + Ui.time(event.getEndsAt() - System.currentTimeMillis()),
                        NamedTextColor.AQUA));
                case "COMPLETED" -> lore.add(Ui.click("claim " + event.getGrade() + " loot"));
                default -> lore.add(Ui.status(event.getState(), NamedTextColor.GRAY));
            }
        }
        return Icon.of(Material.COMPASS)
                .name("Exploration", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(lore).build();
    }

    private void handleExploration(NodeRecord node, ExplorationService.EventRecord event) {
        if (event == null) {
            viewer.sendMessage(Component.text("No Exploration event is available.",
                    NamedTextColor.YELLOW));
            return;
        }
        if ("AVAILABLE".equals(event.getState())) {
            String error = gui.explorationService().start(node, Integer.MAX_VALUE);
            viewer.sendMessage(Component.text(error == null ? "Expedition sent." : error,
                    error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (error == null) {
                gui.npcManager().refreshNode(node, viewer.getWorld());
            }
        } else if ("COMPLETED".equals(event.getState())) {
            var result = gui.explorationService()
                    .claimToWarehouse(node, gui.warehouseService());
            viewer.sendMessage(Component.text(result.message(),
                    result.success() ? NamedTextColor.GOLD : NamedTextColor.RED));
            if (result.success()) {
                gui.npcManager().refreshNode(node, viewer.getWorld());
            }
        } else {
            viewer.sendMessage(Component.text("The expedition is still in progress.",
                    NamedTextColor.YELLOW));
        }
        redraw();
    }

    private void drawUpgrade(NodeRecord node) {
        if (node.isUpgrading()) {
            set(24, Icon.of(Material.SCAFFOLDING)
                    .name("Building Tier " + (node.getTier() + 1), NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.status("UPGRADE IN PROGRESS", NamedTextColor.YELLOW),
                            Ui.line("Ready in " + Ui.time(node.getUpgradeEndsAt()
                                    - System.currentTimeMillis()), NamedTextColor.AQUA),
                            Ui.line("Production continues", NamedTextColor.GRAY)))
                    .build());
            return;
        }
        if (node.getTier() >= gui.claimService().maxTier()) {
            set(24, Icon.of(Material.ANVIL)
                    .name("Tier " + node.getTier() + " | MAX", NamedTextColor.GOLD)
                    .loreComponents(List.of(Ui.status("FULLY UPGRADED", NamedTextColor.GOLD)))
                    .build());
            return;
        }
        double cost = gui.claimService().tierCost(node.getTier());
        set(24, Icon.of(Material.ANVIL)
                .name("Upgrade to Tier " + (node.getTier() + 1), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("Adds one Worker slot", NamedTextColor.GRAY),
                        Ui.line("Cost: " + Ui.num(cost), NamedTextColor.GOLD),
                        Ui.line("Build time: " + Ui.time(gui.claimService()
                                .buildSeconds(node.getTier() + 1) * 1000L),
                                NamedTextColor.AQUA),
                        Ui.click("review purchase")))
                .build(), event -> confirmUpgrade(node, cost));
    }

    private void confirmUpgrade(NodeRecord node, double cost) {
        new ConfirmMenu(viewer, "Upgrade to Tier " + (node.getTier() + 1) + "?",
                List.of("Cost: " + format(cost),
                        "Adds one Worker slot",
                        "Production continues while building"),
                () -> {
                    var result = gui.claimService()
                            .startUpgrade(viewer.getUniqueId(), node);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new NodeControlMenu(viewer, gui, nodeId).open();
                },
                () -> new NodeControlMenu(viewer, gui, nodeId).open()).open();
    }

    private void drawFocus(NodeRecord node) {
        if (gui.gameDesignService() == null) {
            return;
        }
        boolean focused = gui.gameDesignService().isFocused(node);
        set(29, Icon.of(focused ? Material.RECOVERY_COMPASS : Material.COMPASS)
                .name(focused ? "Focused Node" : "Set Focus", focused
                        ? NamedTextColor.AQUA : NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line(focused ? "Daily progression is directed here"
                                        : "Direct daily progression to this Node",
                                NamedTextColor.GRAY),
                        focused ? Ui.status("ACTIVE", NamedTextColor.GREEN)
                                : Ui.click("set focus (24h cooldown)")))
                .build(), event -> {
                    var result = gui.gameDesignService()
                            .setFocus(viewer.getUniqueId(), node, false);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    redraw();
                });
    }

    private void drawBuild(NodeRecord node) {
        if (gui.gameDesignService() == null) {
            return;
        }
        set(31, Icon.of(Material.SMITHING_TABLE)
                .name("Specialization", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("Path: " + gui.gameDesignService().specialization(node),
                                NamedTextColor.GRAY),
                        Ui.line("Refinement: " + gui.gameDesignService().refinement(node),
                                NamedTextColor.GRAY),
                        Ui.line("Mastery: " + gui.gameDesignService().mastery(node),
                                NamedTextColor.GRAY),
                        Ui.click("manage Node build")))
                .build(), event -> new NodeBuildMenu(viewer, gui, nodeId).open());
    }

    private ItemStack convertIcon(NodeRecord node) {
        double cost = gui.plugin().getConfig().getDouble("claims.convert-cost", 750.0);
        return Icon.of(Material.CRAFTING_TABLE)
                .name("Convert Node", NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line("Cost: " + format(cost), NamedTextColor.GOLD),
                        Ui.line("Keeps Tier | Exploration level is halved",
                                NamedTextColor.GRAY),
                        Ui.line("Requires an empty buffer", NamedTextColor.GRAY),
                        Ui.click("choose a new type")))
                .build();
    }

    private ItemStack unclaimIcon(NodeRecord node) {
        double refund = gui.claimService().claimCost(node.getType())
                * gui.plugin().getConfig()
                .getDouble("claims.unclaim-refund-ratio", 0.5);
        return Icon.of(Material.TNT)
                .name("Unclaim Node", NamedTextColor.RED)
                .loreComponents(List.of(
                        Ui.line("Refund: " + format(refund), NamedTextColor.GOLD),
                        Ui.line("Exploration progress will be lost", NamedTextColor.RED),
                        Ui.click("review destructive action")))
                .build();
    }

    private void confirmUnclaim(NodeRecord node) {
        double refund = gui.claimService().claimCost(node.getType())
                * gui.plugin().getConfig()
                .getDouble("claims.unclaim-refund-ratio", 0.5);
        new ConfirmMenu(viewer, "Unclaim " + Ui.pretty(node.getType().name()) + "?",
                List.of("Refund: " + format(refund),
                        "Exploration progress will be lost",
                        "Workers return as contracts",
                        "Buffer must be empty"),
                () -> {
                    var result = gui.claimService().unclaim(viewer.getUniqueId(),
                            viewer.getWorld(), node.getChunk());
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    if (result.success()) {
                        gui.openNodes(viewer);
                    } else {
                        new NodeControlMenu(viewer, gui, nodeId).open();
                    }
                },
                () -> new NodeControlMenu(viewer, gui, nodeId).open()).open();
    }

    private void openAssignPicker(NodeRecord node) {
        new WorkerPickerMenu(viewer, gui, null, null, "Assign a Worker",
                choice -> {
                    WorkerRecord worker = gui.workerStore().get(choice.workerUuid());
                    boolean available = worker != null
                            && (WorkerRecord.STATE_ITEM.equals(worker.getState())
                            || (worker.isInBag()
                            && viewer.getUniqueId().equals(worker.getOwnerUuid())));
                    if (!available) {
                        viewer.sendMessage(Component.text(
                                "That Worker is no longer available.", NamedTextColor.RED));
                        new NodeControlMenu(viewer, gui, nodeId).open();
                        return;
                    }
                    boolean looseItem = WorkerRecord.STATE_ITEM.equals(worker.getState());
                    var result = gui.workerService()
                            .assign(viewer.getUniqueId(), worker, node);
                    if (result.success()) {
                        if (looseItem) {
                            WorkerPickerMenu.consumeFromInventory(
                                    viewer, gui, choice.workerUuid());
                        }
                        gui.npcManager().refreshNode(node, viewer.getWorld());
                    }
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new NodeControlMenu(viewer, gui, nodeId).open();
                },
                () -> new NodeControlMenu(viewer, gui, nodeId).open()).open();
    }

    private ItemStack workerIcon(WorkerRecord worker) {
        List<Component> lore = new ArrayList<>(gui.workerService().workerLore(worker));
        lore.add(Ui.status(workerState(worker), workerStateColor(worker)));
        lore.add(Ui.click("manage Worker"));
        return Icon.head(gui.skinHeadCache(), worker.getSkin())
                .name(worker.getName(), worker.getRarity().color())
                .loreComponents(lore).build();
    }

    private void eject(WorkerRecord worker, NodeRecord node) {
        var result = gui.workerService().eject(viewer.getUniqueId(), worker);
        if (result.success()) {
            if (result.item() != null) {
                giveOrDrop(result.item());
            }
            gui.npcManager().refreshNode(node, viewer.getWorld());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
    }

    private String workerState(WorkerRecord worker) {
        return switch (worker.getState()) {
            case WorkerRecord.STATE_WORKING -> "WORKING";
            case WorkerRecord.STATE_STOP -> "STOPPED";
            case WorkerRecord.STATE_EXPLORING -> "EXPLORING";
            default -> "IDLE";
        };
    }

    private NamedTextColor workerStateColor(WorkerRecord worker) {
        return switch (worker.getState()) {
            case WorkerRecord.STATE_WORKING -> NamedTextColor.GREEN;
            case WorkerRecord.STATE_STOP -> NamedTextColor.RED;
            case WorkerRecord.STATE_EXPLORING -> NamedTextColor.AQUA;
            default -> NamedTextColor.YELLOW;
        };
    }

    private int bufferCapacity(NodeRecord node) {
        double multiplier = gui.gameDesignService() == null ? 1.0
                : gui.gameDesignService().bufferMultiplier(node);
        return (int) Math.round(gui.plugin().getConfig()
                .getInt("production.buffer-capacity-per-tier", 256)
                * node.getTier() * multiplier);
    }

    private void giveOrDrop(ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
        }
    }

    private String format(double value) {
        return value == Math.floor(value)
                ? String.valueOf((long) value) : String.format("%.2f", value);
    }
}
