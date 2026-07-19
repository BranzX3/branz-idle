package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Target-type picker for converting a production node in place. */
public final class ConvertTypeMenu extends Menu {

    private final GuiManager gui;
    private final NodeRecord node;

    public ConvertTypeMenu(Player viewer, GuiManager gui, NodeRecord node) {
        super(viewer);
        this.gui = gui;
        this.node = node;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text("Convert " + node.getType() + " →", NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }
        option(10, NodeType.MINING, Material.IRON_PICKAXE);
        option(11, NodeType.FARMING, Material.WHEAT);
        option(13, NodeType.WOODCUTTING, Material.OAK_LOG);
        option(15, NodeType.LIVESTOCK, Material.BEEF);
        option(16, NodeType.HUNTER, Material.IRON_SWORD);
        set(22, Icon.of(Material.BARRIER).name("Back", NamedTextColor.RED).build(),
                e -> new NodeDetailMenu(viewer, gui, node.getId()).open());
    }

    private void option(int slot, NodeType type, Material material) {
        if (type == node.getType()) {
            set(slot, Icon.of(material).name(type + " (current)", NamedTextColor.DARK_GRAY).build());
            return;
        }
        double cost = gui.plugin().getConfig().getDouble("claims.convert-cost", 750.0);
        set(slot, Icon.of(material).name("→ " + type, NamedTextColor.GREEN)
                .lore(List.of("Cost: " + cost, "Exploration Lv. "
                        + node.getExplorationLevel() + " → "
                        + (int) Math.floor(node.getExplorationLevel()
                                * gui.plugin().getConfig().getDouble("claims.convert-exploration-keep", 0.5))),
                        NamedTextColor.GRAY).build(),
                e -> {
                    var result = gui.claimService().convert(viewer.getUniqueId(), viewer.getWorld(),
                            node.getChunk(), type);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new NodeDetailMenu(viewer, gui, node.getId()).open();
                });
    }
}
