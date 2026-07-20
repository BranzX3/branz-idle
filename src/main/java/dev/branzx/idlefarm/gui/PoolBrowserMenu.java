package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.DropTableService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** Admin: pick a node type, then a bracket (or flat), to open the pool editor. */
public final class PoolBrowserMenu extends Menu {

    private final GuiManager gui;
    private final DropTableService drops;
    private final AuditService audit;
    private final NodeType type; // null = type picker; set = bracket picker
    private final int page;

    public PoolBrowserMenu(Player viewer, GuiManager gui, DropTableService drops, AuditService audit) {
        this(viewer, gui, drops, audit, null, 0);
    }

    private PoolBrowserMenu(Player viewer, GuiManager gui, DropTableService drops,
                            AuditService audit, NodeType type, int page) {
        super(viewer);
        this.gui = gui;
        this.drops = drops;
        this.audit = audit;
        this.type = type;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(type == null ? "Drop Pools — pick type"
                : "Pools: " + type + " — pick bracket", NamedTextColor.DARK_RED);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }
        if (type == null) {
            int slot = 10;
            for (NodeType t : NodeType.values()) {
                if (!t.isProduction()) {
                    continue;
                }
                int brackets = drops.brackets(t).size();
                set(slot++, Icon.of(iconFor(t)).name(t.name(), NamedTextColor.GREEN)
                        .lore(brackets == 0 ? "Flat table" : brackets + " brackets", NamedTextColor.GRAY)
                        .build(), e -> new PoolBrowserMenu(viewer, gui, drops, audit, t, 0).open());
            }
            backButton(49, "Admin Content", () -> new AdminContentMenu(viewer, gui).open());
            return;
        }

        List<String> brackets = drops.brackets(type);
        String typeKey = type.name().toLowerCase(Locale.ROOT);
        if (brackets.isEmpty()) {
            set(13, Icon.of(Material.PAPER).name("Edit flat table", NamedTextColor.GREEN).build(),
                    e -> new PoolEditorMenu(viewer, gui, drops, audit, typeKey).open());
        } else {
            int start = page * 45;
            int slot = 0;
            for (int index = start; index < brackets.size() && slot < 45; index++) {
                String bracket = brackets.get(index);
                set(slot++, Icon.of(Material.PAPER)
                        .name(type + " " + bracket, NamedTextColor.GREEN)
                        .lore("Click to edit this bracket", NamedTextColor.GRAY).build(),
                        e -> new PoolEditorMenu(viewer, gui, drops, audit, typeKey + "." + bracket).open());
            }
        }
        int highest = brackets.stream().mapToInt(this::bracketNumber).max().orElse(0);
        int next = Math.max(1, highest + 1);
        int maximum = Math.max(10,
                gui.plugin().getConfig().getInt("exploration.max-brackets", 100));
        if (next <= maximum) {
            set(47, Icon.of(Material.LIME_DYE)
                    .name("Stage Bracket " + next, NamedTextColor.AQUA)
                    .lore("Add held items, validate, then publish", NamedTextColor.GRAY).build(),
                    e -> new PoolEditorMenu(viewer, gui, drops, audit,
                            typeKey + ".bracket-" + next).open());
        }
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("Previous", NamedTextColor.YELLOW).build(),
                    e -> new PoolBrowserMenu(viewer, gui, drops, audit, type, page - 1).open());
        }
        if ((page + 1) * 45 < brackets.size()) {
            set(53, Icon.of(Material.ARROW).name("Next", NamedTextColor.YELLOW).build(),
                    e -> new PoolBrowserMenu(viewer, gui, drops, audit, type, page + 1).open());
        }
        set(49, Icon.of(Material.BARRIER).name("Back", NamedTextColor.RED).build(),
                e -> new PoolBrowserMenu(viewer, gui, drops, audit, null, 0).open());
    }

    private int bracketNumber(String value) {
        try {
            return Integer.parseInt(value.substring("bracket-".length()));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private Material iconFor(NodeType t) {
        return switch (t) {
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.BEEF;
            case HUNTER -> Material.IRON_SWORD;
            default -> Material.PAPER;
        };
    }
}
