package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.PerkService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Residential-only controls; production actions never appear here. */
public final class ResidentialMenu extends Menu {

    private final GuiManager gui;
    private final long nodeId;

    public ResidentialMenu(Player viewer, GuiManager gui, long nodeId) {
        super(viewer);
        this.gui = gui;
        this.nodeId = nodeId;
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm | Residential", NamedTextColor.YELLOW);
    }

    @Override
    protected void build() {
        NodeRecord node = node();
        if (node == null) {
            viewer.closeInventory();
            return;
        }
        fill();
        set(4, Icon.of(Material.OAK_DOOR)
                .name("Home Plot", NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line("Chunk " + node.getChunk().x() + ", "
                                + node.getChunk().z(), NamedTextColor.GRAY),
                        Ui.status("BUILDING SPACE | NO NODE CAP", NamedTextColor.GREEN)))
                .build());

        boolean closed = gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.NO_VISITS);
        set(11, Icon.of(closed ? Material.IRON_DOOR : Material.OAK_DOOR)
                .name(closed ? "Visits: Closed" : "Visits: Open",
                        closed ? NamedTextColor.RED : NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line("Public visitors never receive build access", NamedTextColor.GRAY),
                        Ui.click("toggle privacy")))
                .build(), event -> {
                    viewer.performCommand("idle visit toggle");
                    redraw();
                });

        set(13, Icon.of(Material.OAK_HANGING_SIGN)
                .name("Trust & Access", NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line(gui.nodeStore().getTrustedOf(viewer.getUniqueId()).size()
                                + " trusted player(s)", NamedTextColor.GRAY),
                        Ui.click("manage roles")))
                .build(), event -> gui.openTrust(viewer));

        set(15, Icon.of(Material.BRICKS)
                .name("Settlement Projects", NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.line("Project buildings appear near your home", NamedTextColor.GRAY),
                        Ui.click("open Progress")))
                .build(), event -> gui.openProgress(viewer));

        set(27, Icon.of(Material.FILLED_MAP)
                .name("Territory Map", NamedTextColor.GREEN).build(),
                event -> gui.openTerritoryMap(viewer));
        set(31, Icon.of(Material.TNT)
                .name("Unclaim Home Plot", NamedTextColor.RED)
                .loreComponents(List.of(
                        Ui.line("Territory must remain connected", NamedTextColor.GRAY),
                        Ui.click("review destructive action")))
                .build(), event -> confirmUnclaim(node));
    }

    private NodeRecord node() {
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(node -> node.getId() == nodeId && !node.getType().isProduction())
                .findFirst().orElse(null);
    }

    private void confirmUnclaim(NodeRecord node) {
        new ConfirmMenu(viewer, "Unclaim this Residential plot?",
                List.of("Territory must remain connected",
                        "The oldest home anchor may relocate",
                        "Building restoration rules apply"),
                () -> {
                    var result = gui.claimService().unclaim(viewer.getUniqueId(),
                            viewer.getWorld(), node.getChunk());
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    gui.openTerritoryMap(viewer);
                },
                () -> new ResidentialMenu(viewer, gui, nodeId).open()).open();
    }
}
