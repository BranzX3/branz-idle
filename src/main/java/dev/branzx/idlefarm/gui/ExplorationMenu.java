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
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm | Exploration", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        NodeRecord node = node();
        if (node == null) {
            viewer.closeInventory();
            return;
        }
        fill();
        ExplorationService service = gui.explorationService();
        ExplorationService.EventRecord event = service.getEvent(nodeId);
        set(4, header(node, event));

        if (event == null) {
            set(22, Icon.of(Material.CLOCK)
                    .name("No Event Available", NamedTextColor.GRAY)
                    .lore("Production continues while you wait", NamedTextColor.DARK_GRAY)
                    .build());
        } else if ("AVAILABLE".equals(event.getState())) {
            drawAvailable(node, event);
        } else if ("RUNNING".equals(event.getState())) {
            set(22, Icon.of(Material.CLOCK)
                    .name("Expedition in Progress", NamedTextColor.AQUA)
                    .loreComponents(List.of(
                            Ui.status("RUNNING", NamedTextColor.AQUA),
                            Ui.line("Returns in " + Ui.time(event.getEndsAt()
                                    - System.currentTimeMillis()), NamedTextColor.GRAY),
                            Ui.line("Committed Workers are away", NamedTextColor.DARK_GRAY)))
                    .build());
        } else if ("COMPLETED".equals(event.getState())) {
            set(22, Icon.of(Material.CHEST)
                    .name(event.getGrade() + " Loot Ready", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Rewards remain safe until claimed", NamedTextColor.GRAY),
                            Ui.line("Warehouse free space: "
                                    + gui.warehouseService().freeSpace(viewer.getUniqueId()),
                                    NamedTextColor.AQUA),
                            Ui.click("claim to Warehouse")))
                    .build(), click -> claim(node));
        }
        backButton(40, "Node Control",
                () -> new NodeControlMenu(viewer, gui, nodeId).open());
    }

    private org.bukkit.inventory.ItemStack header(
            NodeRecord node, ExplorationService.EventRecord event) {
        String name = event == null ? "Exploration"
                : gui.explorationService().eventName(event.getEventType());
        return Icon.of(Material.COMPASS)
                .name(name, NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line(Ui.pretty(node.getType().name())
                                + " | Exploration Lv." + node.getExplorationLevel(),
                                NamedTextColor.AQUA),
                        Ui.line("Bracket " + gui.explorationService().bracket(node),
                                NamedTextColor.GRAY)))
                .build();
    }

    private void drawAvailable(NodeRecord node, ExplorationService.EventRecord event) {
        int available = (int) gui.workerStore().getAssigned(nodeId).stream()
                .filter(worker -> !dev.branzx.idlefarm.worker.WorkerRecord.STATE_EXPLORING
                        .equals(worker.getState())).count();
        teamSize = Math.max(1, Math.min(teamSize, Math.max(1, available)));
        var preview = gui.explorationService().preview(node, teamSize);

        set(19, Icon.of(Material.RED_DYE)
                .name("Smaller Team", NamedTextColor.RED)
                .lore("Current selection: " + teamSize, NamedTextColor.GRAY)
                .build(), click -> {
                    teamSize = Math.max(1, teamSize - 1);
                    redraw();
                });
        set(21, Icon.of(Material.PLAYER_HEAD)
                .name("Team Preview | " + preview.workers(), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("Duration: " + Ui.time(preview.durationMillis()),
                                NamedTextColor.GRAY),
                        Ui.line(String.format("Great %.1f%% | Jackpot %.1f%%",
                                preview.greatChance(), preview.jackpotChance()),
                                NamedTextColor.GOLD),
                        Ui.line("Available Workers: " + available, NamedTextColor.DARK_GRAY)))
                .build());
        set(23, Icon.of(Material.LIME_DYE)
                .name("Larger Team", NamedTextColor.GREEN)
                .lore("Current selection: " + teamSize, NamedTextColor.GRAY)
                .build(), click -> {
                    teamSize = Math.min(Math.max(1, available), teamSize + 1);
                    redraw();
                });

        set(28, preparationIcon(Material.FEATHER, "Speed Route",
                "SPEED", "15% shorter duration"), click ->
                confirmPreparation(node, "SPEED", "15% shorter duration"));
        set(30, preparationIcon(Material.CHEST, "Quantity Kit",
                "QUANTITY", "Improves loot quantity"), click ->
                confirmPreparation(node, "QUANTITY", "Improves loot quantity"));
        set(32, preparationIcon(Material.EXPERIENCE_BOTTLE, "Research Kit",
                "RESEARCH", "Adds Node EXP after completion"), click ->
                confirmPreparation(node, "RESEARCH", "Adds Node EXP after completion"));

        set(24, Icon.of(Material.LIME_CONCRETE)
                .name("Send Expedition", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(preview.workers() + " Workers for "
                                + Ui.time(preview.durationMillis()), NamedTextColor.GRAY),
                        Ui.click("review commitment")))
                .build(), click -> new ConfirmMenu(viewer, "Send this team?",
                        List.of(preview.workers() + " Workers",
                                "Duration: " + Ui.time(preview.durationMillis()),
                                "Workers stop baseline production while away",
                                "Loot is guaranteed"),
                        () -> start(node),
                        this::open).open());
    }

    private org.bukkit.inventory.ItemStack preparationIcon(
            Material material, String label, String option, String effect) {
        int cost = gui.plugin().getConfig()
                .getInt("exploration.preparation-kit-cost", 16);
        return Icon.of(material).name(label, NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line(effect, NamedTextColor.GRAY),
                        Ui.line("Consumes " + cost + " matching resources",
                                NamedTextColor.GOLD),
                        Ui.click("prepare next expedition")))
                .build();
    }

    private void confirmPreparation(NodeRecord node, String option, String effect) {
        int cost = gui.plugin().getConfig()
                .getInt("exploration.preparation-kit-cost", 16);
        new ConfirmMenu(viewer, "Prepare " + Ui.pretty(option) + "?",
                List.of(effect, "Consumes " + cost + " matching resources",
                        "Applies to the next expedition"),
                () -> {
                    var result = gui.gameDesignService()
                            .prepareExpedition(viewer.getUniqueId(), node, option);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    open();
                },
                this::open).open();
    }

    private void start(NodeRecord node) {
        String error = gui.explorationService().start(node, teamSize);
        viewer.sendMessage(Component.text(error == null ? "Expedition sent." : error,
                error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (error == null) {
            gui.npcManager().refreshNode(node, viewer.getWorld());
        }
        new ExplorationMenu(viewer, gui, nodeId, teamSize).open();
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
}
