package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Conversion target picker followed by a destructive confirmation. */
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
        return Component.text("Convert " + Ui.pretty(node.getType().name()),
                NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        fill();
        option(10, NodeType.MINING, Material.IRON_PICKAXE);
        option(11, NodeType.FARMING, Material.WHEAT);
        option(13, NodeType.WOODCUTTING, Material.OAK_LOG);
        option(15, NodeType.LIVESTOCK, Material.BEEF);
        option(16, NodeType.HUNTER, Material.IRON_SWORD);
        backButton(22, "Node Control",
                () -> new NodeControlMenu(viewer, gui, node.getId()).open());
    }

    private void option(int slot, NodeType type, Material material) {
        if (type == node.getType()) {
            set(slot, Icon.of(material)
                    .name(Ui.pretty(type.name()) + " | CURRENT", NamedTextColor.DARK_GRAY)
                    .build());
            return;
        }
        double cost = gui.plugin().getConfig().getDouble("claims.convert-cost", 750.0);
        int resultingLevel = (int) Math.floor(node.getExplorationLevel()
                * gui.plugin().getConfig()
                .getDouble("claims.convert-exploration-keep", 0.5));
        List<String> details = List.of(
                "Cost: " + cost,
                "Exploration Lv." + node.getExplorationLevel() + " -> " + resultingLevel,
                "Workers return as contracts",
                "Buffer must be empty");
        set(slot, Icon.of(material)
                .name(Ui.pretty(type.name()), NamedTextColor.GREEN)
                .loreComponents(java.util.stream.Stream.concat(
                        details.stream().map(line -> Ui.line(line, NamedTextColor.GRAY)),
                        java.util.stream.Stream.of(Ui.click("review conversion")))
                        .toList())
                .build(), event -> confirm(type, details));
    }

    private void confirm(NodeType type, List<String> details) {
        new ConfirmMenu(viewer, "Convert to " + Ui.pretty(type.name()) + "?",
                details,
                () -> {
                    var result = gui.claimService().convert(viewer.getUniqueId(),
                            viewer.getWorld(), node.getChunk(), type);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new NodeControlMenu(viewer, gui, node.getId()).open();
                },
                () -> new ConvertTypeMenu(viewer, gui, node).open()).open();
    }
}
