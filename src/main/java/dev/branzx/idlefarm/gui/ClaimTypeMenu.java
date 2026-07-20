package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Type picker for claiming a chunk from the territory map. */
public final class ClaimTypeMenu extends Menu {

    private final GuiManager gui;
    private final ChunkKey chunk;

    public ClaimTypeMenu(Player viewer, GuiManager gui, ChunkKey chunk) {
        super(viewer);
        this.gui = gui;
        this.chunk = chunk;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text("Claim " + chunk.x() + "," + chunk.z(), NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }
        boolean needsResidential = !gui.claimService().hasResidential(viewer.getUniqueId());
        // Production nodes require an existing Residential; guide the first
        // claim to Residential only.
        option(10, NodeType.RESIDENTIAL, Material.OAK_DOOR, true);
        option(11, NodeType.MINING, Material.IRON_PICKAXE, !needsResidential);
        option(12, NodeType.FARMING, Material.WHEAT, !needsResidential);
        option(14, NodeType.WOODCUTTING, Material.OAK_LOG, !needsResidential);
        option(15, NodeType.LIVESTOCK, Material.BEEF, !needsResidential);
        option(16, NodeType.HUNTER, Material.IRON_SWORD, !needsResidential);
        set(22, Icon.of(Material.BARRIER).name("Back", NamedTextColor.RED).build(),
                e -> new TerritoryMapMenu(viewer, gui).open());
    }

    private void option(int slot, NodeType type, Material material, boolean enabled) {
        double cost = gui.claimService().claimCost(type);
        if (!enabled) {
            set(slot, Icon.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name(type.name() + " (locked)", NamedTextColor.DARK_GRAY)
                    .lore("Claim a Residential plot first", NamedTextColor.DARK_GRAY).build());
            return;
        }
        List<String> lore = type.isProduction()
                ? List.of("Cost: " + Ui.num(cost), "Counts against node cap")
                : List.of("Cost: " + Ui.num(cost), "Building plot, no cap cost",
                        "Residential: " + gui.claimService().countResidential(viewer.getUniqueId())
                                + "/" + gui.claimService().residentialCap(viewer.getUniqueId()));
        set(slot, Icon.of(material).name(Ui.pretty(type.name()), NamedTextColor.GREEN)
                .loreComponents(java.util.stream.Stream.concat(
                        lore.stream().map(line -> Ui.line(line, NamedTextColor.GRAY)),
                        java.util.stream.Stream.of(Ui.click("review claim")))
                        .toList()).build(), e -> new ConfirmMenu(viewer,
                "Claim " + Ui.pretty(type.name()) + "?",
                lore,
                () -> {
                    var result = gui.claimService().claim(viewer.getUniqueId(),
                            viewer.getWorld(), chunk, type);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new TerritoryMapMenu(viewer, gui).open();
                },
                () -> new ClaimTypeMenu(viewer, gui, chunk).open()).open());
    }
}
