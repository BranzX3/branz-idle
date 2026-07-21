package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.ExplorationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Event detail, team-size preview, preparation and safe reward claim flow. */
public final class ExplorationMenu extends Menu {

    private final GuiManager gui;
    private final long nodeId;
    private int teamSize;

    public ExplorationMenu(Player viewer, GuiManager gui, long nodeId) {
        this(viewer, gui, nodeId, 1);
    }

    private ExplorationMenu(Player viewer, GuiManager gui, long nodeId, int teamSize) {
        super(viewer);
        this.gui = gui;
        this.nodeId = nodeId;
        this.teamSize = Math.max(1, teamSize);
    }

    @Override
    protected int rows() {
        // Six rows so "Send Expedition" gets a row of its own: the commit step
        // must not share a row with Back and Close.
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.exploration.title"), NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        NodeRecord node = node();
        if (node == null) {
            viewer.closeInventory();
            return;
        }
        ExplorationService service = gui.explorationService();
        ExplorationService.EventRecord event = service.getEvent(nodeId);
        set(SUMMARY_SLOT, header(node, event));

        if (event == null) {
            set(22, Icon.of(Material.CLOCK)
                    .name(Lang.get("menu.exploration.none.name"), NamedTextColor.GRAY)
                    .lore(Lang.get("menu.exploration.none.hint"), NamedTextColor.DARK_GRAY)
                    .build());
        } else if ("AVAILABLE".equals(event.getState())) {
            drawAvailable(node, event);
        } else if ("RUNNING".equals(event.getState())) {
            set(22, Icon.of(Material.CLOCK)
                    .name(Lang.get("menu.exploration.running.name"), NamedTextColor.AQUA)
                    .loreComponents(List.of(
                            Ui.status(Lang.get("menu.exploration.running.badge"),
                                    NamedTextColor.AQUA),
                            Lang.line("menu.exploration.running.returns", NamedTextColor.GRAY,
                                    "time", Ui.time(event.getEndsAt()
                                            - System.currentTimeMillis())),
                            Lang.line("menu.exploration.running.away",
                                    NamedTextColor.DARK_GRAY)))
                    .build());
        } else if ("COMPLETED".equals(event.getState())) {
            set(22, Icon.of(Material.CHEST)
                    .name(Lang.get("menu.exploration.loot.name",
                            "grade", event.getGrade()), NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Lang.line("menu.exploration.loot.safe", NamedTextColor.GRAY),
                            Lang.line("menu.exploration.loot.space", NamedTextColor.AQUA,
                                    "space", gui.warehouseService()
                                            .freeSpace(viewer.getUniqueId())),
                            Lang.click("menu.exploration.loot.click")))
                    .build(), click -> claim(node));
        }
        navBar(Lang.get("menu.exploration.back"),
                () -> new NodeControlMenu(viewer, gui, nodeId).open());
    }

    private org.bukkit.inventory.ItemStack header(
            NodeRecord node, ExplorationService.EventRecord event) {
        String name = event == null ? Lang.get("menu.exploration.title")
                : gui.explorationService().eventName(event.getEventType());
        return Icon.of(Material.COMPASS)
                .name(name, NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.exploration.header.node", NamedTextColor.AQUA,
                                "type", Ui.pretty(node.getType().name()),
                                "level", node.getExplorationLevel()),
                        Lang.line("menu.exploration.header.bracket", NamedTextColor.GRAY,
                                "bracket", gui.explorationService().bracket(node))))
                .build();
    }

    private void drawAvailable(NodeRecord node, ExplorationService.EventRecord event) {
        int available = (int) gui.workerStore().getAssigned(nodeId).stream()
                .filter(worker -> !dev.branzx.idlefarm.worker.WorkerRecord.STATE_EXPLORING
                        .equals(worker.getState())).count();
        teamSize = Math.max(1, Math.min(teamSize, Math.max(1, available)));
        var preview = gui.explorationService().preview(node, teamSize);

        // Team size: minus / preview / plus, reading left to right.
        set(19, Icon.of(Material.RED_DYE)
                .name(Lang.get("menu.exploration.team.smaller"), NamedTextColor.RED)
                .lore(Lang.get("menu.exploration.team.current", "size", teamSize),
                        NamedTextColor.GRAY)
                .build(), click -> {
                    teamSize = Math.max(1, teamSize - 1);
                    redraw();
                });
        set(21, Icon.of(Material.PLAYER_HEAD)
                .name(Lang.get("menu.exploration.team.preview",
                        "workers", preview.workers()), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.exploration.team.duration", NamedTextColor.GRAY,
                                "time", Ui.time(preview.durationMillis())),
                        Lang.line("menu.exploration.team.odds", NamedTextColor.GOLD,
                                "great", String.format("%.1f", preview.greatChance()),
                                "jackpot", String.format("%.1f", preview.jackpotChance())),
                        Lang.line("menu.exploration.team.available",
                                NamedTextColor.DARK_GRAY, "count", available)))
                .build());
        set(23, Icon.of(Material.LIME_DYE)
                .name(Lang.get("menu.exploration.team.larger"), NamedTextColor.GREEN)
                .lore(Lang.get("menu.exploration.team.current", "size", teamSize),
                        NamedTextColor.GRAY)
                .build(), click -> {
                    teamSize = Math.min(Math.max(1, available), teamSize + 1);
                    redraw();
                });

        preparation(28, Material.FEATHER, "speed", "SPEED", node);
        preparation(30, Material.CHEST, "quantity", "QUANTITY", node);
        preparation(32, Material.EXPERIENCE_BOTTLE, "research", "RESEARCH", node);

        setConfirm(40, Icon.of(Material.LIME_CONCRETE)
                .name(Lang.get("menu.exploration.send.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.exploration.send.commit", NamedTextColor.GRAY,
                                "workers", preview.workers(),
                                "time", Ui.time(preview.durationMillis())),
                        Lang.line("menu.exploration.send.stops", NamedTextColor.GRAY),
                        Lang.line("menu.exploration.send.guaranteed", NamedTextColor.GOLD),
                        Lang.click("menu.exploration.send.click")))
                .build(), () -> start(node));
    }

    private void preparation(int slot, Material material, String key, String option,
                             NodeRecord node) {
        int cost = gui.plugin().getConfig().getInt("exploration.preparation-kit-cost", 16);
        setConfirm(slot, Icon.of(material)
                .name(Lang.get("menu.exploration.prep." + key + ".name"),
                        NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Lang.line("menu.exploration.prep." + key + ".effect",
                                NamedTextColor.GRAY),
                        Lang.line("menu.exploration.prep.cost", NamedTextColor.GOLD,
                                "cost", cost),
                        Lang.line("menu.exploration.prep.applies", NamedTextColor.GRAY),
                        Lang.click("menu.exploration.prep.click")))
                .build(), () -> {
                    var result = gui.gameDesignService()
                            .prepareExpedition(viewer.getUniqueId(), node, option);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    redraw();
                });
    }


    private void start(NodeRecord node) {
        String error = gui.explorationService().start(node, teamSize);
        viewer.sendMessage(Component.text(
                error == null ? Lang.get("menu.exploration.send.done") : error,
                error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (error == null) {
            gui.npcManager().refreshNode(node, viewer.getWorld());
        }
        redraw();
    }

    private void claim(NodeRecord node) {
        var result = gui.explorationService()
                .claimToWarehouse(node, gui.warehouseService());
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GOLD : NamedTextColor.RED));
        if (result.success()) {
            gui.npcManager().refreshNode(node, viewer.getWorld());
        }
        open();
    }

    private NodeRecord node() {
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(node -> node.getId() == nodeId)
                .findFirst().orElse(null);
    }

    @Override
    protected Material frameMaterial() {
        return Material.PURPLE_STAINED_GLASS_PANE;
    }
}
