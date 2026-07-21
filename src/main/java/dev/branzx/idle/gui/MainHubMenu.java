package dev.branzx.idle.gui;

import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.service.PerkService;
import dev.branzx.idle.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen: two centred navigation rows for the core loop and secondary tools,
 * with the account summary and the one recommended action pinned to the bottom bar
 * so they sit next to the player's own hotbar.
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
        return Component.text(Lang.get("menu.home.title"), NamedTextColor.DARK_GREEN);
    }

    /** Two 4-slot navigation rows, vertically centred and sharing a centre column. */
    private static final int[] PRIMARY_ROW = {19, 21, 23, 25};
    private static final int[] SECONDARY_ROW = {28, 30, 32, 34};

    /** Bottom bar: player status on the left, Close centred, server tools on the right. */
    private static final int PROFILE_SLOT = 45;
    private static final int NEXT_ACTION_SLOT = 46;
    private static final int COLLECT_SLOT = 51;
    private static final int LEADERBOARD_SLOT = 52;
    private static final int ADMIN_SLOT = 53;

    @Override
    protected void build() {
        PlayerData data = gui.dataStore().getOnline(viewer.getUniqueId());
        List<NodeRecord> owned = gui.nodeStore().getByOwner(viewer.getUniqueId());
        List<NodeRecord> productionNodes = owned.stream()
                .filter(node -> node.getType().isProduction()).toList();
        long fullBuffers = productionNodes.stream()
                .filter(node -> "STORAGE_FULL".equals(node.getState())).count();
        long eventsReady = productionNodes.stream().filter(this::hasReadyEvent).count();

        set(PROFILE_SLOT, profile(data, productionNodes.size()),
                event -> gui.openProfile(viewer));
        setNextAction(productionNodes, fullBuffers, eventsReady);

        set(PRIMARY_ROW[0], Icon.of(Material.FILLED_MAP)
                .name(Lang.get("menu.home.territory.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.home.territory.claimed", NamedTextColor.GRAY,
                                "chunks", owned.size()),
                        Lang.click("menu.home.territory.click")))
                .build(), event -> gui.openTerritoryMap(viewer));

        set(PRIMARY_ROW[1], Icon.of(Material.GRASS_BLOCK)
                .name(Lang.get("menu.home.nodes.name"),
                        fullBuffers > 0 ? NamedTextColor.RED : NamedTextColor.GREEN)
                .loreComponents(nodeLore(productionNodes, fullBuffers, eventsReady))
                .build(), event -> gui.openNodes(viewer));

        var warehouse = gui.warehouseService();
        int vault = warehouse.vaultTotal(viewer.getUniqueId());
        int vaultCapacity = warehouse.vaultCapacity(viewer.getUniqueId());
        int silo = warehouse.siloTotal(viewer.getUniqueId());
        int siloCapacity = warehouse.siloCapacity(viewer.getUniqueId());
        boolean storageFull = vault >= vaultCapacity || silo >= siloCapacity;
        set(PRIMARY_ROW[2], Icon.of(Material.CHEST)
                .name(Lang.get("menu.home.warehouse.name"),
                        storageFull ? NamedTextColor.RED : NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.bar(Lang.get("menu.home.warehouse.bar-vault"),
                                ratio(vault, vaultCapacity),
                                vault >= vaultCapacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                                Ui.num(vault) + "/" + Ui.num(vaultCapacity)),
                        Ui.bar(Lang.get("menu.home.warehouse.bar-silo"),
                                ratio(silo, siloCapacity),
                                silo >= siloCapacity ? NamedTextColor.RED : NamedTextColor.AQUA,
                                Ui.num(silo) + "/" + Ui.num(siloCapacity)),
                        Lang.click("menu.home.warehouse.click")))
                .build(), event -> gui.openWarehouse(viewer, viewer.getUniqueId()));

        set(PRIMARY_ROW[3], Icon.of(Material.WRITABLE_BOOK)
                .name(Lang.get("menu.home.progress.name"), NamedTextColor.AQUA)
                .loreComponents(progressLore(owned))
                .build(), event -> gui.openProgress(viewer));

        set(SECONDARY_ROW[0], Icon.of(Material.VILLAGER_SPAWN_EGG)
                .name(Lang.get("menu.home.crew.name"), NamedTextColor.AQUA)
                .loreComponents(crewLore(productionNodes))
                .build(), event -> gui.openWorkers(viewer));

        if (gui.expeditionService() != null) {
            set(SECONDARY_ROW[1], Icon.of(Material.CAMPFIRE)
                    .name(Lang.get("menu.home.expedition.name"), NamedTextColor.GOLD)
                    .loreComponents(expeditionLore())
                    .build(), event -> gui.openExpedition(viewer));
        }

        set(SECONDARY_ROW[2], Icon.of(Material.EMERALD)
                .name(Lang.get("menu.home.upgrades.name"), NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.home.upgrades.hint", NamedTextColor.GRAY),
                        Lang.click("menu.home.upgrades.click")))
                .build(), event -> gui.openShop(viewer));

        set(SECONDARY_ROW[3], Icon.of(Material.OAK_HANGING_SIGN)
                .name(Lang.get("menu.home.social.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.home.social.hint", NamedTextColor.GRAY),
                        Lang.click("menu.home.social.click")))
                .build(), event -> gui.openSocial(viewer));

        if (gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.REMOTE_COLLECT)) {
            set(COLLECT_SLOT, Icon.of(Material.HOPPER_MINECART)
                    .name(Lang.get("menu.home.collect-all.name"), NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Lang.line("menu.home.collect-all.hint", NamedTextColor.GRAY),
                            Lang.click("menu.home.collect-all.click")))
                    .build(), event -> collectAll(productionNodes));
        }

        set(LEADERBOARD_SLOT, Icon.of(Material.GOLD_INGOT)
                .name(Lang.get("menu.home.leaderboard.name"), NamedTextColor.GOLD)
                .lore(Lang.get("menu.home.leaderboard.hint"), NamedTextColor.GRAY)
                .build(), event -> gui.openLeaderboard(viewer));

        navBar();

        if (gui.hasAdminAccess(viewer)) {
            set(ADMIN_SLOT, Icon.of(Material.COMMAND_BLOCK)
                    .name(Lang.get("menu.home.admin.name"), NamedTextColor.RED)
                    .lore(Lang.get("menu.home.admin.hint"), NamedTextColor.GRAY)
                    .build(), event -> gui.openAdminHub(viewer));
        }
    }

    private List<Component> nodeLore(List<NodeRecord> productionNodes, long fullBuffers,
                                     long eventsReady) {
        int cap = gui.claimService().nodeCap(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Ui.bar(Lang.get("menu.home.nodes.bar-nodes"),
                ratio(productionNodes.size(), cap), NamedTextColor.GREEN,
                productionNodes.size() + "/" + cap));
        lore.add(Ui.bar(Lang.get("menu.home.nodes.bar-buffers"),
                ratio(fullBuffers, productionNodes.size()),
                fullBuffers > 0 ? NamedTextColor.RED : NamedTextColor.DARK_GREEN,
                Lang.get("menu.home.nodes.buffers-full", "count", fullBuffers)));
        if (eventsReady > 0) {
            lore.add(Lang.line("menu.home.nodes.exploration-ready", NamedTextColor.GOLD,
                    "count", eventsReady));
        }
        lore.add(Lang.click("menu.home.nodes.click"));
        return lore;
    }

    private List<Component> crewLore(List<NodeRecord> productionNodes) {
        long staffed = productionNodes.stream()
                .filter(node -> !gui.workerStore().getAssigned(node.getId()).isEmpty()).count();
        return List.of(
                Ui.bar(Lang.get("menu.home.crew.bar-staffed"),
                        ratio(staffed, productionNodes.size()),
                        staffed < productionNodes.size() ? NamedTextColor.YELLOW
                                : NamedTextColor.GREEN,
                        staffed + "/" + productionNodes.size()),
                Lang.click("menu.home.crew.click"));
    }

    private static double ratio(double value, double max) {
        return max <= 0 ? 0 : value / max;
    }

    private ItemStack profile(PlayerData data, int productionNodes) {
        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        double balance = data == null ? 0 : data.getBalance();
        int streak = gui.streakService() == null ? 0
                : gui.streakService().currentStreak(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Lang.line("menu.home.profile.balance", NamedTextColor.GOLD,
                "balance", Ui.num(balance), "currency", currency));
        lore.add(Lang.line("menu.home.profile.summary", NamedTextColor.GREEN,
                "nodes", productionNodes, "streak", streak));
        if (gui.boosterService() != null) {
            long production = gui.boosterService()
                    .remainingMillis(viewer.getUniqueId(), "production");
            if (production > 0) {
                lore.add(Lang.line("menu.home.profile.boost", NamedTextColor.LIGHT_PURPLE,
                        "time", Ui.time(production)));
            }
        }
        lore.add(Lang.click("menu.home.profile.click"));
        return Icon.head(gui.skinHeadCache(), viewer.getName())
                .name(viewer.getName(), NamedTextColor.YELLOW)
                .loreComponents(lore).build();
    }

    private void setNextAction(List<NodeRecord> nodes, long fullBuffers, long eventsReady) {
        if (nodes.isEmpty()) {
            set(NEXT_ACTION_SLOT, Icon.of(Material.COMPASS)
                    .name(Lang.get("menu.home.next.first-node.name"), NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Lang.line("menu.home.next.first-node.hint", NamedTextColor.GRAY),
                            Lang.click("menu.home.next.first-node.click")))
                    .build(), event -> gui.openTerritoryMap(viewer));
            return;
        }
        if (fullBuffers > 0) {
            NodeRecord target = nodes.stream()
                    .filter(node -> "STORAGE_FULL".equals(node.getState()))
                    .findFirst().orElseThrow();
            set(NEXT_ACTION_SLOT, Icon.of(Material.HOPPER)
                    .name(Lang.get("menu.home.next.collect.name"), NamedTextColor.RED)
                    .loreComponents(List.of(
                            Lang.line("menu.home.next.collect.hint", NamedTextColor.GRAY,
                                    "type", target.getType()),
                            Lang.click("menu.home.next.collect.click")))
                    .build(), event -> gui.openNodeDetail(viewer, target));
            return;
        }
        if (eventsReady > 0) {
            NodeRecord target = nodes.stream().filter(this::hasReadyEvent)
                    .findFirst().orElseThrow();
            set(NEXT_ACTION_SLOT, Icon.of(Material.FIREWORK_STAR)
                    .name(Lang.get("menu.home.next.exploration.name"), NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Lang.line("menu.home.next.exploration.hint", NamedTextColor.GRAY,
                                    "type", target.getType()),
                            Lang.click("menu.home.next.exploration.click")))
                    .build(), event -> gui.openNodeDetail(viewer, target));
            return;
        }
        NodeRecord empty = nodes.stream()
                .filter(node -> gui.workerStore().getAssigned(node.getId()).isEmpty())
                .findFirst().orElse(null);
        if (empty != null) {
            set(NEXT_ACTION_SLOT, Icon.of(Material.PLAYER_HEAD)
                    .name(Lang.get("menu.home.next.worker.name"), NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Lang.line("menu.home.next.worker.hint", NamedTextColor.GRAY,
                                    "type", empty.getType()),
                            Lang.click("menu.home.next.worker.click")))
                    .build(), event -> gui.openNodeDetail(viewer, empty));
            return;
        }
        NodeRecord focused = focusedNode(nodes);
        set(NEXT_ACTION_SLOT, Icon.of(Material.LIME_DYE)
                .name(Lang.get("menu.home.next.idle.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.home.next.idle.hint", NamedTextColor.AQUA,
                                "type", focused.getType(),
                                "level", focused.getExplorationLevel()),
                        Lang.click("menu.home.next.idle.click")))
                .build(), event -> gui.openNodeDetail(viewer, focused));
    }

    private List<Component> progressLore(List<NodeRecord> nodes) {
        NodeRecord focused = focusedNodeOrNull(nodes);
        if (focused == null) {
            return List.of(
                    Lang.line("menu.home.progress.none", NamedTextColor.YELLOW),
                    Lang.click("menu.home.progress.click"));
        }
        return List.of(
                Lang.line("menu.home.progress.focused", NamedTextColor.AQUA,
                        "type", focused.getType(), "level", focused.getExplorationLevel()),
                Lang.click("menu.home.progress.click"));
    }

    private List<Component> expeditionLore() {
        return List.of(
                Lang.line("menu.home.expedition.summary", NamedTextColor.GOLD,
                        "week", gui.expeditionService().activeWeek(),
                        "contribution", Ui.num(
                                gui.expeditionService().contributionOf(viewer.getUniqueId()))),
                Lang.click("menu.home.expedition.click"));
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
            if (node.getStorage().isEmpty() && node.getBulkStorage().isEmpty()) {
                continue;
            }
            int nodeMoved = gui.warehouseService().collectNode(node);
            moved += nodeMoved;
            if (gui.gameDesignService() != null) {
                gui.gameDesignService().onBufferCollected(node, nodeMoved);
            }
        }
        viewer.sendMessage(Component.text(
                Lang.get("menu.home.collect-all.result", "count", moved), NamedTextColor.GREEN));
        redraw();
    }

    @Override
    protected Material frameMaterial() {
        return Material.GREEN_STAINED_GLASS_PANE;
    }
}
