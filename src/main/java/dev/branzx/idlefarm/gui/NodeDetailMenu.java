package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.ExplorationService;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Node Detail: worker slots (row 2), collect / tier / exploration / convert
 * actions (bottom row). Worker items placed into slot slots are assigned;
 * clicking an occupied slot ejects. Item movement is allowed only for the
 * slot region and mediated by handlers.
 */
public final class NodeDetailMenu extends Menu {

    private final GuiManager gui;
    private final long nodeId;

    public NodeDetailMenu(Player viewer, GuiManager gui, long nodeId) {
        super(viewer);
        this.gui = gui;
        this.nodeId = nodeId;
    }

    private NodeRecord node() {
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(n -> n.getId() == nodeId).findFirst()
                .orElseGet(() -> gui.nodeStore().getAll().stream()
                        .filter(n -> n.getId() == nodeId).findFirst().orElse(null));
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        NodeRecord node = node();
        return Component.text(node == null ? "Node" : node.getType() + " T" + node.getTier(),
                NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        NodeRecord node = node();
        if (node == null) {
            viewer.closeInventory();
            return;
        }
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }

        // Header: node summary.
        List<WorkerRecord> headerCrew = gui.workerStore().getAssigned(node.getId());
        List<Component> headerLore = new java.util.ArrayList<>();
        headerLore.add(Ui.line("Chunk " + node.getChunk().x() + "," + node.getChunk().z(),
                NamedTextColor.GRAY));
        headerLore.add(Ui.line("Tier " + roman(node.getTier()) + "  •  " + headerCrew.size() + "/"
                + node.getTier() + " workers", NamedTextColor.AQUA));
        headerLore.add("STORAGE_FULL".equals(node.getState())
                ? Ui.line("■ Production halted — buffer full", NamedTextColor.RED)
                : (headerCrew.isEmpty()
                        ? Ui.line("○ No workers — assign contracts!", NamedTextColor.YELLOW)
                        : Ui.line("● Producing", NamedTextColor.GREEN)));
        set(4, Icon.of(Material.LECTERN).name(node.getType() + " Node", NamedTextColor.GREEN)
                .loreComponents(headerLore).build());

        // Worker slots (row 2): one per tier.
        List<WorkerRecord> assigned = headerCrew;
        for (int slot = 0; slot < node.getTier() && slot < 9; slot++) {
            int guiSlot = 9 + slot;
            if (slot < assigned.size()) {
                WorkerRecord worker = assigned.get(slot);
                set(guiSlot, workerIcon(worker), e -> {
                    var result = gui.workerService().eject(viewer.getUniqueId(), worker);
                    if (result.success() && result.item() != null) {
                        giveOrDrop(result.item());
                        gui.npcManager().refreshNode(node, viewer.getWorld());
                    }
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    redraw();
                });
            } else {
                set(guiSlot, Icon.of(Material.ITEM_FRAME)
                        .name("Empty Slot " + (slot + 1), NamedTextColor.AQUA)
                        .lore("Click to choose a worker", NamedTextColor.GRAY).build(),
                        e -> openAssignPicker(node));
            }
        }

        // Collect.
        int cap = gui.plugin().getConfig().getInt("production.buffer-capacity-per-tier", 64) * node.getTier();
        List<Component> bufferLore = new java.util.ArrayList<>();
        bufferLore.add(Ui.bar("Buffer", cap == 0 ? 0 : node.storageTotal() / (double) cap,
                node.storageTotal() >= cap ? NamedTextColor.RED : NamedTextColor.GOLD,
                node.storageTotal() + "/" + cap));
        if (!node.getStorage().isEmpty()) {
            bufferLore.add(Ui.divider());
            node.getStorage().entrySet().stream().limit(6).forEach(entry ->
                    bufferLore.add(Ui.line(" • " + Ui.pretty(entry.getKey()) + " ×" + entry.getValue(),
                            NamedTextColor.WHITE)));
            if (node.getStorage().size() > 6) {
                bufferLore.add(Ui.line("  …and " + (node.getStorage().size() - 6) + " more",
                        NamedTextColor.DARK_GRAY));
            }
        }
        bufferLore.add(Ui.line("Click: collect to Warehouse", NamedTextColor.DARK_GRAY));
        set(29, Icon.of(Material.HOPPER).name("Collect → Warehouse", NamedTextColor.GOLD)
                .loreComponents(bufferLore).build(),
                e -> {
                    int moved = collectToWarehouse(node);
                    node.setState("ACTIVE");
                    gui.nodeStore().updateProduction(node);
                    gui.npcManager().refreshNode(node, viewer.getWorld());
                    viewer.sendMessage(Component.text("Collected " + moved + " to Warehouse.",
                            NamedTextColor.GREEN));
                    redraw();
                });

        // Tier upgrade.
        double upgradeCost = tierCost(node.getTier());
        set(31, Icon.of(Material.ANVIL).name("Upgrade Tier → T" + (node.getTier() + 1), NamedTextColor.AQUA)
                .lore(List.of("Adds a worker slot", "Cost: " + formatAmount(upgradeCost)), NamedTextColor.GRAY).build(),
                e -> upgradeTier(node, upgradeCost));

        // Exploration.
        ExplorationService exploration = gui.explorationService();
        var event = exploration.getEvent(node.getId());
        long expNeeded = exploration.expForNextExplorationLevel(node.getExplorationLevel());
        List<Component> exploreLore = new java.util.ArrayList<>();
        exploreLore.add(Ui.line("Bracket " + roman(exploration.bracket(node)), NamedTextColor.LIGHT_PURPLE));
        exploreLore.add(Ui.bar("Lv." + node.getExplorationLevel(),
                expNeeded <= 0 ? 1.0 : Math.min(1.0, node.getExplorationExp() / (double) expNeeded),
                NamedTextColor.LIGHT_PURPLE,
                Ui.num(node.getExplorationExp()) + "/" + Ui.num(expNeeded)));
        exploreLore.add(Ui.divider());
        if (event == null) {
            exploreLore.add(Ui.line("No event right now", NamedTextColor.DARK_GRAY));
        } else {
            exploreLore.add(Ui.line("★ " + exploration.eventName(event.getEventType()),
                    NamedTextColor.GOLD));
            switch (event.getState()) {
                case "AVAILABLE" -> {
                    exploreLore.add(Ui.line("Expires in "
                            + Ui.time(event.getExpiresAt() - System.currentTimeMillis()),
                            NamedTextColor.YELLOW));
                    exploreLore.add(Ui.line("Click: send expedition!", NamedTextColor.GREEN));
                }
                case "RUNNING" -> exploreLore.add(Ui.line("Returns in "
                        + Ui.time(Math.max(0, event.getEndsAt() - System.currentTimeMillis())),
                        NamedTextColor.AQUA));
                case "COMPLETED" -> exploreLore.add(Ui.line("Click: claim loot! ["
                        + event.getGrade() + "]", NamedTextColor.GOLD));
                default -> { }
            }
        }
        set(33, Icon.of(Material.COMPASS).name("Exploration", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(exploreLore).build(),
                e -> handleExploration(node, event));

        // Convert type.
        double convertCost = gui.plugin().getConfig().getDouble("claims.convert-cost", 750.0);
        set(37, Icon.of(Material.CRAFTING_TABLE).name("Convert Type", NamedTextColor.YELLOW)
                .lore(List.of("Cost: " + formatAmount(convertCost),
                        "Keeps tier; exploration level halved",
                        "Workers return as contracts",
                        "Buffer must be empty"), NamedTextColor.GRAY).build(),
                e -> new ConvertTypeMenu(viewer, gui, node).open());

        // Unclaim (confirm-gated).
        double refund = gui.claimService().claimCost(node.getType())
                * gui.plugin().getConfig().getDouble("claims.unclaim-refund-ratio", 0.5);
        set(43, Icon.of(Material.TNT).name("Unclaim", NamedTextColor.RED)
                .lore(List.of("Refund: " + formatAmount(refund),
                        "Exploration level is LOST"), NamedTextColor.GRAY).build(),
                e -> new ConfirmMenu(viewer, "Unclaim " + node.getType() + "?",
                        List.of("Refund: " + formatAmount(refund),
                                "Exploration level is LOST",
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
                                new NodeDetailMenu(viewer, gui, nodeId).open();
                            }
                        },
                        () -> new NodeDetailMenu(viewer, gui, nodeId).open()).open());

        set(40, Icon.of(Material.NETHER_STAR).name("Back", NamedTextColor.GREEN).build(),
                e -> gui.openNodes(viewer));
    }

    private void openAssignPicker(NodeRecord node) {
        new WorkerPickerMenu(viewer, gui, null, null, "Assign a Worker",
                choice -> {
                    // Re-fetch the live record and consume the item by UUID.
                    WorkerRecord worker = gui.workerStore().get(choice.workerUuid());
                    if (worker == null || !WorkerRecord.STATE_ITEM.equals(worker.getState())) {
                        viewer.sendMessage(Component.text("That worker is no longer available.",
                                NamedTextColor.RED));
                        new NodeDetailMenu(viewer, gui, nodeId).open();
                        return;
                    }
                    // Consume the item first; assign() never fails after its
                    // own checks, but if it did we'd refund below.
                    if (!WorkerPickerMenu.consumeFromInventory(viewer, gui, choice.workerUuid())) {
                        viewer.sendMessage(Component.text("That worker is no longer in your inventory.",
                                NamedTextColor.RED));
                        new NodeDetailMenu(viewer, gui, nodeId).open();
                        return;
                    }
                    var result = gui.workerService().assign(viewer.getUniqueId(), worker, node);
                    if (result.success()) {
                        gui.npcManager().refreshNode(node, viewer.getWorld());
                    } else {
                        giveOrDrop(gui.workerService().createItem(worker)); // refund
                    }
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new NodeDetailMenu(viewer, gui, nodeId).open();
                },
                () -> new NodeDetailMenu(viewer, gui, nodeId).open()).open();
    }

    private void handleExploration(NodeRecord node, ExplorationService.EventRecord event) {
        if (event == null) {
            viewer.sendMessage(Component.text("No exploration event at this node right now.",
                    NamedTextColor.YELLOW));
            return;
        }
        if ("AVAILABLE".equals(event.getState())) {
            String error = gui.explorationService().start(node, Integer.MAX_VALUE);
            viewer.sendMessage(Component.text(error == null ? "Expedition sent!" : error,
                    error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (error == null) {
                gui.npcManager().refreshNode(node, viewer.getWorld());
            }
        } else if ("COMPLETED".equals(event.getState())) {
            var loot = gui.explorationService().claim(node);
            int total = 0;
            if (loot != null) {
                for (var entry : loot.entrySet()) {
                    int stored = gui.warehouseService().deposit(node.getOwnerUuid(), entry.getKey(), entry.getValue());
                    total += stored;
                }
            }
            viewer.sendMessage(Component.text("Expedition loot → Warehouse: " + total + " items!",
                    NamedTextColor.GOLD));
            gui.npcManager().refreshNode(node, viewer.getWorld());
        } else {
            viewer.sendMessage(Component.text("Expedition still in progress.", NamedTextColor.YELLOW));
        }
        redraw();
    }

    private void upgradeTier(NodeRecord node, double cost) {
        PlayerData data = gui.dataStore().getOnline(viewer.getUniqueId());
        if (data == null || data.getBalance() < cost) {
            viewer.sendMessage(Component.text("Not enough money (need " + formatAmount(cost) + ").",
                    NamedTextColor.RED));
            return;
        }
        data.addBalance(-cost);
        node.setTier(node.getTier() + 1);
        gui.nodeStore().updateProduction(node);
        gui.npcManager().refreshNode(node, viewer.getWorld());
        viewer.sendMessage(Component.text("Upgraded to T" + node.getTier() + "!", NamedTextColor.GREEN));
        redraw();
    }

    private double tierCost(int currentTier) {
        double base = gui.plugin().getConfig().getDouble("nodes.tier-base-cost", 1000);
        double factor = gui.plugin().getConfig().getDouble("nodes.tier-cost-factor", 1.8);
        return base * Math.pow(factor, currentTier - 1);
    }

    private int collectToWarehouse(NodeRecord node) {
        int collected = 0;
        for (var entry : List.copyOf(node.getStorage().entrySet())) {
            int stored = gui.warehouseService().deposit(node.getOwnerUuid(), entry.getKey(), entry.getValue());
            collected += stored;
            if (stored >= entry.getValue()) {
                node.getStorage().remove(entry.getKey());
            } else {
                node.getStorage().put(entry.getKey(), entry.getValue() - stored);
                break;
            }
        }
        return collected;
    }

    private ItemStack workerIcon(WorkerRecord worker) {
        List<Component> lore = new ArrayList<>(gui.workerService().workerLore(worker));
        lore.add(Ui.line(stateBadge(worker.getState()), stateColor(worker.getState())));
        lore.add(Ui.line("Click to eject", NamedTextColor.DARK_GRAY));
        return Icon.of(Material.PLAYER_HEAD)
                .name("✦ " + worker.getName(), worker.getRarity().color())
                .loreComponents(lore).build();
    }

    private String stateBadge(String state) {
        return switch (state) {
            case WorkerRecord.STATE_WORKING -> "● Working";
            case WorkerRecord.STATE_STOP -> "■ Stopped (buffer full)";
            case WorkerRecord.STATE_EXPLORING -> "» Away exploring";
            default -> "○ Idle";
        };
    }

    private NamedTextColor stateColor(String state) {
        return switch (state) {
            case WorkerRecord.STATE_WORKING -> NamedTextColor.GREEN;
            case WorkerRecord.STATE_STOP -> NamedTextColor.RED;
            case WorkerRecord.STATE_EXPLORING -> NamedTextColor.AQUA;
            default -> NamedTextColor.YELLOW;
        };
    }

    private String roman(int value) {
        String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return value >= 1 && value <= 10 ? numerals[value - 1] : String.valueOf(value);
    }

    private void giveOrDrop(ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
        }
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }
}
