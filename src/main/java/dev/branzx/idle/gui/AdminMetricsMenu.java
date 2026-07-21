package dev.branzx.idle.gui;

import dev.branzx.idle.service.design.TelemetryService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Economy dashboard with actionable live-ops alerts. */
public final class AdminMetricsMenu extends Menu {

    private final GuiManager gui;
    private final TelemetryService.EconomyDashboard dashboard;

    private AdminMetricsMenu(Player viewer, GuiManager gui,
                             TelemetryService.EconomyDashboard dashboard) {
        super(viewer);
        this.gui = gui;
        this.dashboard = dashboard;
    }

    public static void open(Player viewer, GuiManager gui) {
        viewer.closeInventory();
        viewer.sendMessage(Component.text("Loading economy dashboard…", NamedTextColor.GRAY));
        gui.plugin().getServer().getScheduler().runTaskAsynchronously(gui.plugin(), () -> {
            var loaded = gui.gameDesignService().economyDashboardSync();
            gui.plugin().getServer().getScheduler().runTask(gui.plugin(),
                    () -> new AdminMetricsMenu(viewer, gui, loaded).open());
        });
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return Component.text("Admin • Economy (7 days)", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        set(4, Icon.of(Material.GOLD_INGOT).name("Coin Supply", NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.line("Players: " + Ui.num(dashboard.players()), NamedTextColor.GRAY),
                        Ui.line("Total: " + Ui.num(dashboard.totalCoins()), NamedTextColor.YELLOW),
                        Ui.line("Richest: " + Ui.num(dashboard.richestBalance()), NamedTextColor.AQUA)))
                .build());
        set(10, Icon.of(Material.IRON_PICKAXE).name("Production", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line("Production nodes: " + Ui.num(dashboard.productionNodes()), NamedTextColor.GRAY),
                        Ui.line("Items produced: " + Ui.num(dashboard.itemsProduced7d()), NamedTextColor.GREEN),
                        Ui.line("Warehouse capacity: " + Ui.num(dashboard.warehouseCapacity()),
                                NamedTextColor.AQUA))).build());
        set(12, Icon.of(Material.HOPPER).name("Material Sinks", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("Project sink: " + Ui.num(dashboard.itemsSunk7d()), NamedTextColor.GRAY),
                        Ui.line(String.format("Sink ratio: %.1f%%", dashboard.sinkRatio() * 100),
                                dashboard.sinkRatio() >= 0.10 ? NamedTextColor.GREEN : NamedTextColor.RED)))
                .build());

        var content = gui.dropTableService().status();
        set(14, Icon.of(Material.BOOKSHELF).name("Content Health", NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line(content.draftDirty() ? "Unpublished draft changes" : "Draft is published",
                                content.draftDirty() ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
                        Ui.line("Validation errors: " + content.validationErrors(),
                                content.publishable() ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Ui.line(gui.dropTableService().resourcePolicies(true).size()
                                + " resources covered", NamedTextColor.AQUA))).build(),
                event -> new AdminContentMenu(viewer, gui).open());
        var design = gui.gameDesignService();
        var schedule = design.seasonSchedule();
        List<String> seasonErrors = design.seasonValidationErrors();
        set(16, Icon.of(seasonErrors.isEmpty() ? Material.CLOCK : Material.BARRIER)
                .name("Live Ops • " + design.seasonId(),
                        seasonErrors.isEmpty() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.RED)
                .loreComponents(List.of(
                        Ui.line("Week " + schedule.week() + " • " + schedule.phase(),
                                NamedTextColor.YELLOW),
                        Ui.line("Modifier: " + Ui.pretty(schedule.modifier()), NamedTextColor.AQUA),
                        Ui.line("Event family: " + Ui.pretty(schedule.eventFamily()),
                                NamedTextColor.GRAY),
                        Ui.line(seasonErrors.isEmpty() ? "Schedule valid"
                                        : seasonErrors.size() + " schedule validation errors",
                                seasonErrors.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.RED)))
                .build());

        int slot = 27;
        for (TelemetryService.AdminAlert alert : dashboard.alerts()) {
            if (slot >= 45) break;
            NamedTextColor color = switch (alert.severity()) {
                case INFO -> NamedTextColor.GREEN;
                case WARNING -> NamedTextColor.YELLOW;
                case CRITICAL -> NamedTextColor.RED;
            };
            Material icon = switch (alert.severity()) {
                case INFO -> Material.LIME_DYE;
                case WARNING -> Material.YELLOW_DYE;
                case CRITICAL -> Material.REDSTONE_BLOCK;
            };
            set(slot++, Icon.of(icon).name(alert.code(), color)
                    .lore(alert.message(), NamedTextColor.GRAY).build());
        }
        set(49, Icon.of(Material.ARROW).name("Back to Admin Hub", NamedTextColor.GREEN).build(),
                event -> gui.openAdminHub(viewer));
    }
}
