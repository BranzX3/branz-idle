package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Five-by-nine chunk map centered on the player.
 * Green is actionable, colored items are owned, red belongs to another player.
 */
public final class TerritoryMapMenu extends Menu {

    private static final int GRID_WIDTH = 9;
    private static final int GRID_HEIGHT = 5;

    private final GuiManager gui;

    public TerritoryMapMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.territory.title"), NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        int centerX = viewer.getLocation().getBlockX() >> 4;
        int centerZ = viewer.getLocation().getBlockZ() >> 4;
        String world = viewer.getWorld().getName();

        for (int row = 0; row < GRID_HEIGHT; row++) {
            for (int column = 0; column < GRID_WIDTH; column++) {
                int chunkX = centerX + column - GRID_WIDTH / 2;
                int chunkZ = centerZ + row - GRID_HEIGHT / 2;
                ChunkKey key = new ChunkKey(world, chunkX, chunkZ);
                boolean current = chunkX == centerX && chunkZ == centerZ;
                NodeRecord node = gui.nodeStore().getByChunk(key);
                int slot = row * 9 + column;

                if (node == null) {
                    drawEmpty(slot, key, current);
                } else if (node.getOwnerUuid().equals(viewer.getUniqueId())) {
                    drawOwned(slot, node, current);
                } else {
                    set(slot, Icon.of(Material.RED_DYE)
                            .name(Lang.get("menu.territory.tile.taken"), NamedTextColor.RED)
                            .lore(Lang.get("menu.territory.tile.taken-hint"),
                                    NamedTextColor.GRAY)
                            .build());
                }
            }
        }

        set(navRow() + 1, Icon.of(Material.GRASS_BLOCK)
                .name(Lang.get("menu.territory.nodes.name"), NamedTextColor.GREEN)
                .lore(Lang.get("menu.territory.nodes.hint"), NamedTextColor.GRAY)
                .build(), event -> gui.openNodes(viewer));
        set(navRow() + 7, Icon.of(Material.COMPASS)
                .name(Lang.get("menu.territory.recenter.name"), NamedTextColor.AQUA)
                .lore(Lang.get("menu.territory.recenter.hint"), NamedTextColor.GRAY)
                .build(), event -> new TerritoryMapMenu(viewer, gui).open());
        navBarToHub(gui);
    }

    private void drawEmpty(int slot, ChunkKey key, boolean current) {
        boolean firstClaim = !gui.claimService().hasResidential(viewer.getUniqueId());
        boolean claimable = firstClaim ? current : adjacentToOwn(key);
        // Dye, not stained glass: glass now reads as "background" everywhere
        // else, and these tiles are the actual content of this screen.
        Material material = claimable ? Material.LIME_DYE : Material.GRAY_DYE;
        List<Component> lore = claimable
                ? List.of(
                        Lang.line("menu.territory.tile.free", NamedTextColor.GRAY),
                        Lang.click(firstClaim ? "menu.territory.tile.click-first"
                                : "menu.territory.tile.click-claim"))
                : List.of(
                        Lang.line("menu.territory.tile.free", NamedTextColor.GRAY),
                        Lang.line(firstClaim ? "menu.territory.tile.stand-here"
                                        : "menu.territory.tile.must-touch",
                                NamedTextColor.DARK_GRAY));
        set(slot, Icon.of(material)
                .name(tileName(current, key.x() + ", " + key.z()),
                        claimable ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .loreComponents(lore).build(),
                claimable ? event -> new ClaimTypeMenu(viewer, gui, key).open() : null);
    }

    private void drawOwned(int slot, NodeRecord node, boolean current) {
        set(slot, ownedIcon(node, current), event -> {
            if (node.getType().isProduction()) {
                gui.openNodeDetail(viewer, node);
            } else {
                gui.openResidential(viewer, node);
            }
        });
    }

    private ItemStack ownedIcon(NodeRecord node, boolean current) {
        Material material = switch (node.getType()) {
            case RESIDENTIAL -> Material.OAK_DOOR;
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.BEEF;
            case HUNTER -> Material.IRON_SWORD;
        };
        List<Component> lore = node.getType().isProduction()
                ? List.of(
                        Lang.line("menu.territory.tile.owned", NamedTextColor.GRAY,
                                "tier", node.getTier(), "state", node.getState()),
                        Lang.click("menu.territory.tile.click-node"))
                : List.of(
                        Ui.status(Lang.get("menu.territory.tile.home"), NamedTextColor.YELLOW),
                        Lang.click("menu.territory.tile.click-home"));
        return Icon.of(material)
                .name(tileName(current, Ui.pretty(node.getType().name())),
                        current ? NamedTextColor.AQUA : NamedTextColor.GREEN)
                .loreComponents(lore).build();
    }

    /** Marks the chunk the player is standing in so the map has an anchor. */
    private String tileName(boolean current, String label) {
        return current ? Lang.get("menu.territory.tile.you-are-here", "label", label) : label;
    }

    private boolean adjacentToOwn(ChunkKey key) {
        for (ChunkKey neighbor : key.neighbors()) {
            NodeRecord node = gui.nodeStore().getByChunk(neighbor);
            if (node != null && node.getOwnerUuid().equals(viewer.getUniqueId())) {
                return true;
            }
        }
        return false;
    }


    @Override
    protected Material frameMaterial() {
        return Material.GREEN_STAINED_GLASS_PANE;
    }
}
