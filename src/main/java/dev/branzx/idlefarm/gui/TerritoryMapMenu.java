package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.ChunkKey;
import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 9x5 chunk-grid map centered on the viewer's chunk. Own nodes are colored
 * by type (click → Node Detail; shift-click → unclaim confirm), unclaimed
 * chunks adjacent to the territory open the claim type picker, other
 * players' chunks show red.
 */
public final class TerritoryMapMenu extends Menu {

    private static final int GRID_W = 9;
    private static final int GRID_H = 5;

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
        return Component.text("Territory Map", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        int centerChunkX = viewer.getLocation().getBlockX() >> 4;
        int centerChunkZ = viewer.getLocation().getBlockZ() >> 4;
        String world = viewer.getWorld().getName();

        for (int row = 0; row < GRID_H; row++) {
            for (int col = 0; col < GRID_W; col++) {
                int chunkX = centerChunkX + (col - GRID_W / 2);
                int chunkZ = centerChunkZ + (row - GRID_H / 2);
                ChunkKey key = new ChunkKey(world, chunkX, chunkZ);
                int slot = row * 9 + col;
                boolean isCenter = chunkX == centerChunkX && chunkZ == centerChunkZ;
                NodeRecord node = gui.nodeStore().getByChunk(key);

                if (node == null) {
                    boolean adjacent = adjacentToOwn(key);
                    set(slot, Icon.of(adjacent ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.GRAY_STAINED_GLASS_PANE)
                            .name((isCenter ? "» " : "") + "Chunk " + chunkX + "," + chunkZ,
                                    adjacent ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                            .lore(adjacent ? "Click to claim" : "Unclaimed", NamedTextColor.DARK_GRAY)
                            .build(),
                            adjacent ? e -> new ClaimTypeMenu(viewer, gui, key).open() : null);
                } else if (node.getOwnerUuid().equals(viewer.getUniqueId())) {
                    set(slot, ownIcon(node, isCenter), e -> {
                        if (e.isShiftClick()) {
                            confirmUnclaim(node);
                        } else if (node.getType().isProduction()) {
                            gui.openNodeDetail(viewer, node);
                        }
                    });
                } else {
                    set(slot, Icon.of(Material.RED_STAINED_GLASS_PANE)
                            .name("Claimed", NamedTextColor.RED)
                            .lore("Another player's territory", NamedTextColor.DARK_GRAY).build());
                }
            }
        }

        for (int i = 45; i < 54; i++) {
            set(i, Icon.filler());
        }
        set(49, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
        set(53, Icon.of(Material.CLOCK).name("Refresh", NamedTextColor.YELLOW)
                .lore("Re-centers on your position", NamedTextColor.GRAY).build(),
                e -> new TerritoryMapMenu(viewer, gui).open());
    }

    private ItemStack ownIcon(NodeRecord node, boolean isCenter) {
        Material material = switch (node.getType()) {
            case RESIDENTIAL -> Material.OAK_DOOR;
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.BEEF;
            case HUNTER -> Material.IRON_SWORD;
        };
        return Icon.of(material)
                .name((isCenter ? "» " : "") + node.getType() + " T" + node.getTier(), NamedTextColor.GREEN)
                .lore(List.of("State: " + node.getState(),
                        node.getType().isProduction() ? "Click: manage" : "Your home plot",
                        "Shift-click: unclaim"), NamedTextColor.GRAY)
                .build();
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
                * gui.plugin().getConfig().getDouble("claims.unclaim-refund-ratio", 0.5);
        new ConfirmMenu(viewer, "Unclaim " + node.getType() + "?",
                List.of("Refund: " + refund,
                        "Exploration level is LOST",
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
