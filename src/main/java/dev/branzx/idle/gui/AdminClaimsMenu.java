package dev.branzx.idle.gui;

import dev.branzx.idle.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

public final class AdminClaimsMenu extends Menu {

    private static final int PAGE_SIZE = 45;
    private final GuiManager gui;
    private final OfflinePlayer target;
    private final int page;
    private final Runnable onBack;

    public AdminClaimsMenu(Player viewer, GuiManager gui, OfflinePlayer target, int page, Runnable onBack) {
        super(viewer);
        this.gui = gui;
        this.target = target;
        this.page = Math.max(0, page);
        this.onBack = onBack;
    }

    public AdminClaimsMenu(Player viewer, GuiManager gui, OfflinePlayer target, int page) {
        this(viewer, gui, target, page, () -> new AdminPlayerMenu(viewer, gui, target).open());
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return Component.text("Claims • " + target.getName(), NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        List<NodeRecord> nodes = gui.nodeStore().getByOwner(target.getUniqueId());
        int start = page * PAGE_SIZE;
        for (int index = 0; index < PAGE_SIZE && start + index < nodes.size(); index++) {
            NodeRecord node = nodes.get(start + index);
            set(index, Icon.of(node.getType().isProduction() ? Material.GRASS_BLOCK : Material.OAK_DOOR)
                    .name("#" + node.getId() + " " + node.getType(), NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Ui.line("Tier " + node.getTier() + " • Lv." + node.getExplorationLevel(),
                                    NamedTextColor.GRAY),
                            Ui.line(node.getChunk().world() + " " + node.getChunk().x()
                                    + "," + node.getChunk().z(), NamedTextColor.DARK_GRAY),
                            Ui.status(node.getState(), NamedTextColor.YELLOW),
                            Ui.click("Teleport และเปิด Node Control"))).build(),
                    event -> {
                        var world = gui.plugin().getServer().getWorld(node.getChunk().world());
                        if (world == null) {
                            viewer.sendMessage(Component.text("World ยังไม่ได้ load",
                                    NamedTextColor.RED));
                            return;
                        }
                        viewer.teleport(new org.bukkit.Location(world,
                                (node.getChunk().x() << 4) + 8.5,
                                Math.max(world.getMinHeight() + 1, node.getOriginY() + 1),
                                (node.getChunk().z() << 4) + 8.5));
                        new AdminNodeMenu(viewer, gui, node, () -> new AdminClaimsMenu(viewer, gui, target, page, onBack).open()).open();
                    });
        }
        set(49, Icon.of(Material.ARROW).name("กลับ Player Control", NamedTextColor.GREEN).build(),
                event -> onBack.run());
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("ก่อนหน้า", NamedTextColor.YELLOW).build(),
                    event -> new AdminClaimsMenu(viewer, gui, target, page - 1, onBack).open());
        }
        if (start + PAGE_SIZE < nodes.size()) {
            set(53, Icon.of(Material.ARROW).name("ถัดไป", NamedTextColor.YELLOW).build(),
                    event -> new AdminClaimsMenu(viewer, gui, target, page + 1, onBack).open());
        }
    }
}
