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
        return Component.text("IdleFarm | Territory", NamedTextColor.DARK_GREEN);
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
                    set(slot, Icon.of(Material.RED_STAINED_GLASS_PANE)
                            .name("Claimed Territory", NamedTextColor.RED)
                            .lore("Owned by another player", NamedTextColor.GRAY)
                            .build());
                }
            }
        }

        for (int slot = 45; slot < 54; slot++) {
            set(slot, Icon.filler());
        }
        set(45, Icon.of(Material.GRASS_BLOCK)
                .name("All Nodes", NamedTextColor.GREEN)
                .lore("Open the status-first Node list", NamedTextColor.GRAY)
                .build(), event -> gui.openNodes(viewer));
        set(49, Icon.of(Material.NETHER_STAR)
                .name("Back to Hub", NamedTextColor.GREEN)
                .build(), event -> gui.openMainHub(viewer));
        set(53, Icon.of(Material.COMPASS)
                .name("Recenter Map", NamedTextColor.AQUA)
                .lore("Center the map on your current chunk", NamedTextColor.GRAY)
                .build(), event -> new TerritoryMapMenu(viewer, gui).open());
    }

    private void drawEmpty(int slot, ChunkKey key, boolean current) {
        boolean firstClaim = !gui.claimService().hasResidential(viewer.getUniqueId());
        boolean claimable = firstClaim ? current : adjacentToOwn(key);
        Material material = claimable
                ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        List<Component> lore = claimable
                ? List.of(
                        Ui.line("Unclaimed", NamedTextColor.GRAY),
                        Ui.click(firstClaim ? "build your Residential plot" : "claim this chunk"))
                : List.of(
                        Ui.line("Unclaimed", NamedTextColor.GRAY),
                        Ui.line(firstClaim ? "Stand in this chunk to begin"
                                        : "Must touch your existing territory",
                                NamedTextColor.DARK_GRAY));
        set(slot, Icon.of(material)
                .name((current ? "YOU ARE HERE | " : "")
                                + key.x() + ", " + key.z(),
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
                        Ui.line("Tier " + node.getTier() + " | " + node.getState(),
                                NamedTextColor.GRAY),
                        Ui.click("open Node Control"))
                : List.of(
                        Ui.status("HOME PLOT", NamedTextColor.YELLOW),
                        Ui.click("open Residential controls"));
        return Icon.of(material)
                .name((current ? "YOU ARE HERE | " : "")
                                + Ui.pretty(node.getType().name()),
                        current ? NamedTextColor.AQUA : NamedTextColor.GREEN)
                .loreComponents(lore).build();
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

    private void confirmUnclaim(NodeRecord node) {
        double refund = gui.claimService().claimCost(node.getType())
                * gui.plugin().getConfig()
                .getDouble("claims.unclaim-refund-ratio", 0.5);
        new ConfirmMenu(viewer, "Unclaim " + Ui.pretty(node.getType().name()) + "?",
                List.of("Refund: " + refund,
                        "Exploration progress will be lost",
                        "Workers return as contracts",
                        "Buffer must be empty"),
                () -> {
                    var result = gui.claimService().unclaim(viewer.getUniqueId(),
                            viewer.getWorld(), node.getChunk());
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new TerritoryMapMenu(viewer, gui).open();
                },
                () -> new TerritoryMapMenu(viewer, gui).open()).open();
    }
}
