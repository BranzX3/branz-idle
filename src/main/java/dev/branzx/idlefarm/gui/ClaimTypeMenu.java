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
        option(10, NodeType.RESIDENTIAL, Material.OAK_DOOR);
        option(11, NodeType.MINING, Material.IRON_PICKAXE);
        option(12, NodeType.FARMING, Material.WHEAT);
        option(14, NodeType.WOODCUTTING, Material.OAK_LOG);
        option(15, NodeType.LIVESTOCK, Material.BEEF);
        option(16, NodeType.HUNTER, Material.IRON_SWORD);
        set(22, Icon.of(Material.BARRIER).name("Back", NamedTextColor.RED).build(),
                e -> new TerritoryMapMenu(viewer, gui).open());
    }

    private void option(int slot, NodeType type, Material material) {
        double cost = gui.claimService().claimCost(type);
        List<String> lore = type.isProduction()
                ? List.of("Cost: " + cost, "Counts against node cap")
                : List.of("Cost: " + cost, "Building plot, no cap cost");
        set(slot, Icon.of(material).name(type.name(), NamedTextColor.GREEN)
                .lore(lore, NamedTextColor.GRAY).build(), e -> {
            var result = gui.claimService().claim(viewer.getUniqueId(), viewer.getWorld(), chunk, type);
            viewer.sendMessage(Component.text(result.message(),
                    result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
            new TerritoryMapMenu(viewer, gui).open();
        });
    }
}
