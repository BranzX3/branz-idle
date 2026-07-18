package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class NodesMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final GuiManager gui;
    private final int page;

    public NodesMenu(Player viewer, GuiManager gui, int page) {
        super(viewer);
        this.gui = gui;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Your Nodes", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        List<NodeRecord> nodes = gui.nodeStore().getByOwner(viewer.getUniqueId());
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < nodes.size(); i++) {
            NodeRecord node = nodes.get(start + i);
            set(i, iconFor(node), e -> gui.openNodeDetail(viewer, node));
        }

        for (int i = 45; i < 54; i++) {
            set(i, Icon.filler());
        }
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("Previous", NamedTextColor.YELLOW).build(),
                    e -> new NodesMenu(viewer, gui, page - 1).open());
        }
        set(49, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
        if (start + PAGE_SIZE < nodes.size()) {
            set(53, Icon.of(Material.ARROW).name("Next", NamedTextColor.YELLOW).build(),
                    e -> new NodesMenu(viewer, gui, page + 1).open());
        }
    }

    private org.bukkit.inventory.ItemStack iconFor(NodeRecord node) {
        Material material = switch (node.getType()) {
            case RESIDENTIAL -> Material.OAK_DOOR;
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.BEEF;
            case HUNTER -> Material.IRON_SWORD;
        };
        int cap = gui.plugin().getConfig().getInt("production.buffer-capacity-per-tier", 64) * node.getTier();
        return Icon.of(material)
                .name(node.getType() + " T" + node.getTier(), NamedTextColor.GREEN)
                .lore(List.of(
                        "Chunk " + node.getChunk().x() + "," + node.getChunk().z(),
                        "State: " + node.getState(),
                        node.getType().isProduction()
                                ? "Buffer: " + node.storageTotal() + "/" + cap : "Home plot",
                        node.getType().isProduction()
                                ? "Exploration Lv." + node.getExplorationLevel() : ""),
                        NamedTextColor.GRAY)
                .build();
    }
}
