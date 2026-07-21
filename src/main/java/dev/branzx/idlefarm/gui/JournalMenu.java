package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovery journal.
 *
 * <p>On the old Progress screen this was five cards that could not be clicked
 * and showed only the first four finds each, while looking exactly like the
 * claim buttons beside them. Here the node types are a picker and the entries
 * are the content, so a card that looks clickable is clickable.
 */
public final class JournalMenu extends Menu {

    /** Node-type picker across the top of the content area. */
    private static final int[] TYPES = {10, 12, 14, 16};

    private final GuiManager gui;

    /** Null shows the picker with an overview; set shows that type's finds. */
    private final NodeType selected;

    public JournalMenu(Player viewer, GuiManager gui, NodeType selected) {
        super(viewer);
        this.gui = gui;
        this.selected = selected;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.journal.title"), NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        GameDesignService design = gui.gameDesignService();
        if (design == null) {
            return;
        }
        List<NodeType> types = new ArrayList<>();
        for (NodeType type : NodeType.values()) {
            if (type.isProduction()) {
                types.add(type);
            }
        }

        int total = 0;
        for (NodeType type : types) {
            total += design.discoveries(viewer.getUniqueId(), type).size();
        }
        set(SUMMARY_SLOT, Icon.of(Material.KNOWLEDGE_BOOK)
                .name(Lang.get("menu.journal.summary", "count", total),
                        NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.journal.what", NamedTextColor.GRAY),
                        Lang.line("menu.journal.hidden", NamedTextColor.DARK_GRAY)))
                .build());

        drawTypePicker(design, types);
        if (selected != null) {
            drawEntries(design.discoveries(viewer.getUniqueId(), selected));
        } else {
            set(28, Icon.of(Material.PAPER)
                    .name(Lang.get("menu.journal.pick.name"), NamedTextColor.YELLOW)
                    .lore(Lang.get("menu.journal.pick.hint"), NamedTextColor.GRAY).build());
        }

        navBar(Lang.get("menu.progress.tab.overview"), () -> gui.openProgress(viewer));
    }

    private void drawTypePicker(GameDesignService design, List<NodeType> types) {
        for (int index = 0; index < types.size() && index < TYPES.length; index++) {
            NodeType type = types.get(index);
            boolean active = type == selected;
            int found = design.discoveries(viewer.getUniqueId(), type).size();
            var icon = Icon.of(active ? iconFor(type) : Material.GRAY_DYE)
                    .name(Lang.get("menu.journal.type", "type", ProgressMenu.pretty(type.name())),
                            active ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GRAY)
                    .loreComponents(List.of(
                            Lang.line("menu.journal.found", NamedTextColor.AQUA,
                                    "count", found),
                            Lang.line(active ? "menu.common.tab-active"
                                    : "menu.journal.click", NamedTextColor.DARK_GRAY)))
                    .build();
            set(TYPES[index], icon, active ? null
                    : event -> new JournalMenu(viewer, gui, type).open());
        }
    }

    private void drawEntries(Map<String, Long> entries) {
        if (entries.isEmpty()) {
            set(28, Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.journal.empty.name"), NamedTextColor.GRAY)
                    .lore(Lang.get("menu.journal.empty.hint"), NamedTextColor.DARK_GRAY)
                    .build());
            return;
        }
        int index = 0;
        // Rows 3-4 of the content grid: the picker owns row 1.
        for (Map.Entry<String, Long> entry : entries.entrySet()) {
            if (index + 14 >= CONTENT_GRID.length) {
                break;
            }
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(entry.getKey());
            set(CONTENT_GRID[index + 14], Icon.of(material == null
                            ? Material.PRISMARINE_SHARD : material)
                    .name(ProgressMenu.pretty(entry.getKey()), NamedTextColor.WHITE)
                    .loreComponents(List.of(
                            Lang.line("menu.journal.entry", NamedTextColor.AQUA,
                                    "count", entry.getValue())))
                    .build());
            index++;
        }
    }

    private Material iconFor(NodeType type) {
        return switch (type) {
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.LEATHER;
            case HUNTER -> Material.BOW;
            default -> Material.BOOK;
        };
    }

    @Override
    protected Material frameMaterial() {
        return Material.CYAN_STAINED_GLASS_PANE;
    }
}
