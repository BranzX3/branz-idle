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

        // Worker slots (row 2): one per tier.
        List<WorkerRecord> assigned = gui.workerStore().getAssigned(node.getId());
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
                set(guiSlot, Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                        .name("Empty Slot " + (slot + 1), NamedTextColor.GRAY)
                        .lore("Click with a worker contract to assign", NamedTextColor.DARK_GRAY).build(),
                        e -> assignHeld(node));
            }
        }

        // Collect.
        int cap = gui.plugin().getConfig().getInt("production.buffer-capacity-per-tier", 64) * node.getTier();
        set(29, Icon.of(Material.HOPPER).name("Collect → Warehouse", NamedTextColor.GOLD)
                .lore(List.of("Buffer: " + node.storageTotal() + "/" + cap), NamedTextColor.GRAY).build(),
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
        String exploreLore = event == null ? "No event now"
                : gui.explorationService().eventName(event.getEventType()) + " [" + event.getState() + "]";
        set(33, Icon.of(Material.COMPASS).name("Exploration Lv." + node.getExplorationLevel(),
                        NamedTextColor.LIGHT_PURPLE)
                .lore(List.of("Bracket " + exploration.bracket(node), exploreLore,
                        "Click for actions"), NamedTextColor.GRAY).build(),
                e -> handleExploration(node, event));

        set(40, Icon.of(Material.NETHER_STAR).name("Back", NamedTextColor.GREEN).build(),
                e -> gui.openNodes(viewer));
    }

    private void assignHeld(NodeRecord node) {
        ItemStack held = viewer.getInventory().getItemInMainHand();
        WorkerRecord worker = gui.workerService().fromItem(held);
        if (worker == null) {
            viewer.sendMessage(Component.text("Hold a worker contract in your main hand to assign.",
                    NamedTextColor.RED));
            return;
        }
        var result = gui.workerService().assign(viewer.getUniqueId(), worker, node);
        if (result.success()) {
            held.setAmount(held.getAmount() - 1);
            gui.npcManager().refreshNode(node, viewer.getWorld());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
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
        List<String> lore = new ArrayList<>();
        lore.add("Rarity: " + worker.getRarity());
        lore.add("Trait: " + worker.getTrait());
        lore.add("Lv." + worker.getLevel() + " / " + worker.getRarity().levelCap());
        lore.add("State: " + worker.getState());
        var s = worker.getStats();
        lore.add("DIL " + s.diligence() + " LCK " + s.luck() + " STA " + s.stamina() + " SPD " + s.speed());
        lore.add("Click to eject");
        return Icon.of(Material.PLAYER_HEAD).name(worker.getName(), worker.getRarity().color())
                .lore(lore, NamedTextColor.GRAY).build();
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
