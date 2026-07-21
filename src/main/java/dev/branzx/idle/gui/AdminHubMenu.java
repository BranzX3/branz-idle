package dev.branzx.idle.gui;

import dev.branzx.idle.node.ChunkKey;
import dev.branzx.idle.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Task-oriented admin landing page with permission-aware sections. */
public final class AdminHubMenu extends Menu {

    private final GuiManager gui;

    public AdminHubMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text("Idle | Admin", NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        set(4, Icon.head(gui.skinHeadCache(), viewer.getName())
                .name("Admin | " + viewer.getName(), NamedTextColor.RED)
                .loreComponents(List.of(
                        Ui.line("Choose a task below", NamedTextColor.GRAY),
                        Ui.line("Mutations require a reason and audit ID",
                                NamedTextColor.YELLOW)))
                .build());

        if (can("idle.admin.operations")) {
            set(10, Icon.of(Material.SPYGLASS)
                    .name("Node Inspector", NamedTextColor.AQUA)
                    .loreComponents(nodeLore())
                    .build(), event -> new AdminNodeMenu(viewer, gui).open());
        }
        if (can("idle.admin.content")) {
            set(12, Icon.of(Material.CHEST)
                    .name("Content Control", NamedTextColor.LIGHT_PURPLE)
                    .loreComponents(List.of(
                            Ui.line("Draft, validate, publish and rollback", NamedTextColor.GRAY),
                            Ui.click("open content tools")))
                    .build(), event -> new AdminContentMenu(viewer, gui).open());
            set(14, Icon.of(Material.STRUCTURE_BLOCK)
                    .name("Schematics", NamedTextColor.LIGHT_PURPLE)
                    .loreComponents(List.of(
                            Ui.line("Buildings, anchors and animation profiles",
                                    NamedTextColor.GRAY),
                            Ui.click("open authoring flow")))
                    .build(), event -> new AdminSchematicMenu(viewer, gui).open());
        }
        if (can("idle.admin.audit")) {
            set(16, Icon.of(Material.REDSTONE_TORCH)
                    .name("System Health", NamedTextColor.RED)
                    .loreComponents(List.of(
                            Ui.line("Economy, telemetry and live alerts", NamedTextColor.GRAY),
                            Ui.click("load dashboard")))
                    .build(), event -> AdminMetricsMenu.open(viewer, gui));
        }

        if (can("idle.admin.economy") || can("idle.admin.operations")) {
            set(28, Icon.of(Material.PLAYER_HEAD)
                    .name("Players & Economy", NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Ui.line("Accounts, claims, Coins, Credits and caps",
                                    NamedTextColor.GRAY),
                            Ui.click("search players")))
                    .build(), event -> new AdminPlayerListMenu(viewer, gui, 0).open());
        }
        if (can("idle.admin.operations")) {
            set(30, Icon.of(Material.CLOCK)
                    .name("Events & Operations", NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Spawn events and control the current Node",
                                    NamedTextColor.GRAY),
                            Ui.click("open operations")))
                    .build(), event -> new AdminNodeMenu(viewer, gui).open());
        }
        if (can("idle.admin.audit")) {
            set(32, Icon.of(Material.WRITABLE_BOOK)
                    .name("Audit Log", NamedTextColor.AQUA)
                    .loreComponents(List.of(
                            Ui.line("Recent mutations and economy actions", NamedTextColor.GRAY),
                            Ui.click("browse audit entries")))
                    .build(), event -> AdminLogMenu.open(viewer, gui, null,
                            () -> gui.openAdminHub(viewer)));
        }
        if (can("idle.admin.operations")) {
            set(34, Icon.of(Material.TNT)
                    .name("Danger Zone", NamedTextColor.DARK_RED)
                    .loreComponents(List.of(
                            Ui.line("Force actions require reason and confirmation",
                                    NamedTextColor.RED),
                            Ui.click("inspect current Node first")))
                    .build(), event -> new AdminNodeMenu(viewer, gui).open());
        }
        navBarToHub(gui);
    }

    private List<Component> nodeLore() {
        NodeRecord node = gui.nodeStore().getByChunk(new ChunkKey(
                viewer.getWorld().getName(),
                viewer.getLocation().getBlockX() >> 4,
                viewer.getLocation().getBlockZ() >> 4));
        if (node == null) {
            return List.of(
                    Ui.status("UNCLAIMED CHUNK", NamedTextColor.YELLOW),
                    Ui.line("Stand inside a Node and reopen this menu",
                            NamedTextColor.GRAY));
        }
        return List.of(
                Ui.status(Ui.pretty(node.getType().name()) + " | " + node.getState(),
                        NamedTextColor.GREEN),
                Ui.line("Node #" + node.getId() + " | Tier " + node.getTier(),
                        NamedTextColor.GRAY),
                Ui.click("inspect current Node"));
    }

    private boolean can(String permission) {
        return viewer.hasPermission("idle.admin") || viewer.hasPermission(permission);
    }
}
