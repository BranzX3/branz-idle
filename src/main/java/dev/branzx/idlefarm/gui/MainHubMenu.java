package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.PerkService;
import dev.branzx.idlefarm.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen ordered by player intent:
 * account summary, one recommended action, core loop, then secondary tools.
 */
public final class MainHubMenu extends Menu {

    private final GuiManager gui;

    public MainHubMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm | Home", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        fill();

        PlayerData data = gui.dataStore().getOnline(viewer.getUniqueId());
        List<NodeRecord> owned = gui.nodeStore().getByOwner(viewer.getUniqueId());
        List<NodeRecord> productionNodes = owned.stream()
                .filter(node -> node.getType().isProduction()).toList();
        long fullBuffers = productionNodes.stream()
                .filter(node -> "STORAGE_FULL".equals(node.getState())).count();
        long eventsReady = productionNodes.stream().filter(this::hasReadyEvent).count();

        set(4, profile(data, productionNodes.size()),
                event -> gui.openProfile(viewer));
        setNextAction(productionNodes, fullBuffers, eventsReady);

        set(19, Icon.of(Material.FILLED_MAP)
                .name("Territory", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(owned.size() + " claimed chunks", NamedTextColor.GRAY),
                        Ui.click("view map or claim land")))
                .build(), event -> gui.openTerritoryMap(viewer));

        List<Component> nodeLore = new ArrayList<>();
        nodeLore.add(Ui.line(productionNodes.size() + "/"
                + gui.claimService().nodeCap(viewer.getUniqueId()) + " production nodes",
                NamedTextColor.GRAY));
        if (fullBuffers > 0) {
            nodeLore.add(Ui.line(fullBuffers + " buffer(s) need collecting", NamedTextColor.RED));
        }
        if (eventsReady > 0) {
            nodeLore.add(Ui.line(eventsReady + " exploration action(s) ready", NamedTextColor.GOLD));
        }
        nodeLore.add(Ui.click("open Node Control"));
        set(21, Icon.of(Material.GRASS_BLOCK)
                .name("Node Control", fullBuffers > 0 ? NamedTextColor.RED : NamedTextColor.GREEN)
                .loreComponents(nodeLore).build(), event -> gui.openNodes(viewer));

        int stored = gui.warehouseService().total(viewer.getUniqueId());
        int capacity = gui.warehouseService().getCapacity(viewer.getUniqueId());
        set(23, Icon.of(Material.CHEST)
                .name("Warehouse", stored >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.bar("Storage", capacity == 0 ? 0 : stored / (double) capacity,
                                stored >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                                Ui.num(stored) + "/" + Ui.num(capacity)),
                        Ui.click("deposit or withdraw items")))
                .build(), event -> gui.openWarehouse(viewer, viewer.getUniqueId()));

        set(25, Icon.of(Material.WRITABLE_BOOK)
                .name("Progress", NamedTextColor.AQUA)
                .loreComponents(progressLore(owned))
                .build(), event -> gui.openProgress(viewer));

        set(29, Icon.of(Material.VILLAGER_SPAWN_EGG)
                .name("Crew", NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("Workers, hiring and fusion", NamedTextColor.GRAY),
                        Ui.click("manage your crew")))
                .build(), event -> gui.openWorkers(viewer));

        if (gui.expeditionService() != null) {
            set(31, Icon.of(Material.CAMPFIRE)
                    .name("Weekly Expedition", NamedTextColor.GOLD)
                    .loreComponents(expeditionLore())
                    .build(), event -> gui.openExpedition(viewer));
        }

        set(33, Icon.of(Material.EMERALD)
                .name("Upgrades", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("Boosters and convenience perks", NamedTextColor.GRAY),
                        Ui.click("open upgrades")))
                .build(), event -> gui.openShop(viewer));

        set(35, Icon.of(Material.OAK_HANGING_SIGN)
                .name("Social", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line("Trust, visits and trading", NamedTextColor.GRAY),
                        Ui.click("open Social")))
                .build(), event -> gui.openSocial(viewer));

        if (gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.REMOTE_COLLECT)) {
            set(45, Icon.of(Material.HOPPER_MINECART)
                    .name("Quick Collect All", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Move every Node buffer to Warehouse", NamedTextColor.GRAY),
                            Ui.click("collect now")))
                    .build(), event -> collectAll(productionNodes));
        }

        set(48, Icon.of(Material.GOLD_INGOT)
                .name("Leaderboard", NamedTextColor.GOLD)
                .lore("See the richest players", NamedTextColor.GRAY)
                .build(), event -> gui.openLeaderboard(viewer));

        closeButton();

        if (gui.hasAdminAccess(viewer)) {
            set(53, Icon.of(Material.COMMAND_BLOCK)
                    .name("Admin", NamedTextColor.RED)
                    .lore("Open server management tools", NamedTextColor.GRAY)
                    .build(), event -> gui.openAdminHub(viewer));
        }
    }

    private ItemStack profile(PlayerData data, int productionNodes) {
        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        double balance = data == null ? 0 : data.getBalance();
        int streak = gui.streakService() == null ? 0
                : gui.streakService().currentStreak(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Ui.line(Ui.num(balance) + " " + currency, NamedTextColor.GOLD));
        lore.add(Ui.line(productionNodes + " production nodes", NamedTextColor.GREEN));
        lore.add(Ui.line("Login streak: " + streak + " day(s)", NamedTextColor.RED));
        if (gui.boosterService() != null) {
            long production = gui.boosterService()
                    .remainingMillis(viewer.getUniqueId(), "production");
            if (production > 0) {
                lore.add(Ui.line("Production boost: " + Ui.time(production),
                        NamedTextColor.LIGHT_PURPLE));
            }
        }
        lore.add(Ui.click("show account details in chat"));
        return Icon.head(gui.skinHeadCache(), viewer.getName())
                .name(viewer.getName(), NamedTextColor.YELLOW)
                .loreComponents(lore).build();
    }

    private void setNextAction(List<NodeRecord> nodes, long fullBuffers, long eventsReady) {
        if (nodes.isEmpty()) {
            set(13, Icon.of(Material.COMPASS)
                    .name("Next: Build your first Node", NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line("Choose an adjacent chunk and Node type", NamedTextColor.GRAY),
                            Ui.click("open Territory")))
                    .build(), event -> gui.openTerritoryMap(viewer));
            return;
        }
        if (fullBuffers > 0) {
            NodeRecord target = nodes.stream()
                    .filter(node -> "STORAGE_FULL".equals(node.getState()))
                    .findFirst().orElseThrow();
            set(13, Icon.of(Material.HOPPER)
                    .name("Next: Collect a full buffer", NamedTextColor.RED)
                    .loreComponents(List.of(
                            Ui.line(target.getType() + " production is stopped", NamedTextColor.GRAY),
                            Ui.click("open this Node")))
                    .build(), event -> gui.openNodeDetail(viewer, target));
            return;
        }
        if (eventsReady > 0) {
            NodeRecord target = nodes.stream().filter(this::hasReadyEvent)
                    .findFirst().orElseThrow();
            set(13, Icon.of(Material.FIREWORK_STAR)
                    .name("Next: Exploration is ready", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line(target.getType() + " has an event or reward waiting",
                                    NamedTextColor.GRAY),
                            Ui.click("open this Node")))
                    .build(), event -> gui.openNodeDetail(viewer, target));
            return;
        }
        NodeRecord empty = nodes.stream()
                .filter(node -> gui.workerStore().getAssigned(node.getId()).isEmpty())
                .findFirst().orElse(null);
        if (empty != null) {
            set(13, Icon.of(Material.PLAYER_HEAD)
                    .name("Next: Assign a Worker", NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line(empty.getType() + " cannot produce without a crew",
                                    NamedTextColor.GRAY),
                            Ui.click("open this Node")))
                    .build(), event -> gui.openNodeDetail(viewer, empty));
            return;
        }
        NodeRecord focused = focusedNode(nodes);
        set(13, Icon.of(Material.LIME_DYE)
                .name("All systems running", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(focused.getType() + " | Exploration Lv."
                                + focused.getExplorationLevel(), NamedTextColor.AQUA),
                        Ui.click("open focused Node")))
                .build(), event -> gui.openNodeDetail(viewer, focused));
    }

    private List<Component> progressLore(List<NodeRecord> nodes) {
        NodeRecord focused = focusedNodeOrNull(nodes);
        if (focused == null) {
            return List.of(
                    Ui.line("No Focused Node selected", NamedTextColor.YELLOW),
                    Ui.line("Chronicle, commissions and season goals", NamedTextColor.GRAY),
                    Ui.click("open Progress"));
        }
        return List.of(
                Ui.line("Focused: " + focused.getType() + " Lv."
                        + focused.getExplorationLevel(), NamedTextColor.AQUA),
                Ui.line("Chronicle, commissions and season goals", NamedTextColor.GRAY),
                Ui.click("open Progress"));
    }

    private List<Component> expeditionLore() {
        return List.of(
                Ui.line("Week " + gui.expeditionService().activeWeek(), NamedTextColor.GRAY),
                Ui.line("Contribution: "
                        + Ui.num(gui.expeditionService().contributionOf(viewer.getUniqueId())),
                        NamedTextColor.GOLD),
                Ui.click("view weekly event"));
    }

    private NodeRecord focusedNode(List<NodeRecord> nodes) {
        NodeRecord focused = focusedNodeOrNull(nodes);
        return focused == null ? nodes.getFirst() : focused;
    }

    private NodeRecord focusedNodeOrNull(List<NodeRecord> nodes) {
        Long id = gui.gameDesignService() == null ? null
                : gui.gameDesignService().focusedNode(viewer.getUniqueId());
        if (id == null) {
            return null;
        }
        return nodes.stream().filter(node -> node.getId() == id).findFirst().orElse(null);
    }

    private boolean hasReadyEvent(NodeRecord node) {
        var event = gui.explorationService().getEvent(node.getId());
        return event != null && ("AVAILABLE".equals(event.getState())
                || "COMPLETED".equals(event.getState()));
    }

    private void collectAll(List<NodeRecord> nodes) {
        int moved = 0;
        for (NodeRecord node : nodes) {
            if (node.getStorage().isEmpty()) {
                continue;
            }
            int nodeMoved = gui.warehouseService().collectNode(node);
            moved += nodeMoved;
            if (gui.gameDesignService() != null) {
                gui.gameDesignService().onBufferCollected(node, nodeMoved);
            }
        }
        viewer.sendMessage(Component.text("Collected " + moved + " items to Warehouse.",
                NamedTextColor.GREEN));
        redraw();
    }
}
