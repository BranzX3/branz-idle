package dev.branzx.idle.gui;

import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Paginated list menu to browse all claimed nodes on the server. */
public final class AdminNodeListMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final GuiManager gui;
    private final int page;
    private final Runnable onBack;

    public AdminNodeListMenu(Player viewer, GuiManager gui, int page, Runnable onBack) {
        super(viewer);
        this.gui = gui;
        this.page = Math.max(0, page);
        this.onBack = onBack;
    }

    public AdminNodeListMenu(Player viewer, GuiManager gui, int page) {
        this(viewer, gui, page, () -> gui.openAdminHub(viewer));
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Admin | Node Browser", NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        List<NodeRecord> nodes = new ArrayList<>(gui.nodeStore().getAll());
        nodes.sort(Comparator.comparingLong(NodeRecord::getId));

        int start = page * PAGE_SIZE;
        for (int index = 0; index < PAGE_SIZE && start + index < nodes.size(); index++) {
            NodeRecord node = nodes.get(start + index);
            Material material = getMaterialForType(node.getType());
            
            String ownerName = Bukkit.getOfflinePlayer(node.getOwnerUuid()).getName();
            if (ownerName == null) {
                ownerName = node.getOwnerUuid().toString().substring(0, 8) + "...";
            }

            set(index, Icon.of(material)
                    .name("#" + node.getId() + " " + node.getType().name(), NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Ui.line("Owner: " + ownerName, NamedTextColor.GRAY),
                            Ui.status(node.getState(), NamedTextColor.YELLOW),
                            Ui.line("Tier " + node.getTier() + " • Lv." + node.getExplorationLevel(), NamedTextColor.AQUA),
                            Ui.line("Location: " + node.getChunk().world() + " " + node.getChunk().x() + "," + node.getChunk().z(), NamedTextColor.DARK_GRAY),
                            Ui.click("open Node Control")
                    )).build(),
                    event -> new AdminNodeMenu(viewer, gui, node, () -> new AdminNodeListMenu(viewer, gui, page, onBack).open()).open()
            );
        }

        // Search button
        set(48, Icon.of(Material.NAME_TAG)
                .name("Search Node ID", NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("ค้นหา Node ด้วย ID", NamedTextColor.GRAY),
                        Ui.click("พิมพ์ Node ID")))
                .build(), event -> search());

        backButton(49, "Admin Hub", onBack);

        if (page > 0) {
            set(45, Icon.of(Material.ARROW)
                    .name("Previous Page", NamedTextColor.YELLOW).build(),
                    event -> new AdminNodeListMenu(viewer, gui, page - 1, onBack).open());
        }
        if (start + PAGE_SIZE < nodes.size()) {
            set(53, Icon.of(Material.ARROW)
                    .name("Next Page", NamedTextColor.YELLOW).build(),
                    event -> new AdminNodeListMenu(viewer, gui, page + 1, onBack).open());
        }
    }

    private void search() {
        gui.chatPrompt().request(viewer, "พิมพ์ Node ID ที่ต้องการค้นหา", raw -> {
            try {
                long id = Long.parseLong(raw.trim());
                NodeRecord node = gui.nodeStore().getById(id);
                if (node == null) {
                    viewer.sendMessage(Component.text("ไม่พบ Node ID: " + id, NamedTextColor.RED));
                    open();
                    return;
                }
                new AdminNodeMenu(viewer, gui, node, () -> open()).open();
            } catch (NumberFormatException e) {
                viewer.sendMessage(Component.text("ID ต้องเป็นตัวเลขเท่านั้น", NamedTextColor.RED));
                open();
            }
        }, this::open);
    }

    private Material getMaterialForType(NodeType type) {
        if (type == null) return Material.BARRIER;
        return switch (type) {
            case RESIDENTIAL -> Material.RED_BED;
            case MINING -> Material.IRON_ORE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.LEATHER;
            case HUNTER -> Material.BOW;
        };
    }
}
