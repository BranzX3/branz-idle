package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Status-first production Node browser. Nodes needing attention appear first. */
public final class NodesMenu extends Menu {

    private final GuiManager gui;
    private final int page;

    public NodesMenu(Player viewer, GuiManager gui, int page) {
        super(viewer);
        this.gui = gui;
        this.page = Math.max(0, page);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.nodes.title"), NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        List<NodeRecord> nodes = sortedProductionNodes();
        long attention = nodes.stream().filter(node -> priority(node) < 3).count();

        set(SUMMARY_SLOT, Icon.of(attention > 0 ? Material.BELL : Material.LIME_DYE)
                .name(Lang.get("menu.nodes.summary", "count", nodes.size()),
                        attention > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN)
                .loreComponents(List.of(
                        attention > 0
                                ? Lang.line("menu.nodes.attention", NamedTextColor.YELLOW,
                                        "count", attention)
                                : Lang.line("menu.nodes.all-normal", NamedTextColor.GREEN),
                        Lang.line("menu.nodes.sort-hint", NamedTextColor.GRAY)))
                .build());

        int start = page * CONTENT_GRID.length;
        for (int index = 0; index < CONTENT_GRID.length && start + index < nodes.size(); index++) {
            NodeRecord node = nodes.get(start + index);
            set(CONTENT_GRID[index], iconFor(node), event -> gui.openNodeDetail(viewer, node));
        }

        if (nodes.isEmpty()) {
            set(CONTENT_GRID[10], Icon.of(Material.COMPASS)
                    .name(Lang.get("menu.nodes.empty.name"), NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Lang.line("menu.nodes.empty.hint", NamedTextColor.GRAY),
                            Lang.click("menu.nodes.empty.click")))
                    .build(), event -> gui.openTerritoryMap(viewer));
        }

        set(navRow() + 1, Icon.of(Material.FILLED_MAP)
                .name(Lang.get("menu.nodes.map.name"), NamedTextColor.GREEN)
                .lore(Lang.get("menu.nodes.map.hint"), NamedTextColor.GRAY)
                .build(), event -> gui.openTerritoryMap(viewer));
        set(navRow() + 7, Icon.of(Material.CLOCK)
                .name(Lang.get("menu.nodes.refresh.name"), NamedTextColor.AQUA)
                .lore(Lang.get("menu.nodes.refresh.hint"), NamedTextColor.GRAY)
                .build(), event -> redraw());

        navBarToHub(gui);
        pager(page, pageCount(nodes.size()),
                target -> new NodesMenu(viewer, gui, target).open());
    }


    private List<NodeRecord> sortedProductionNodes() {
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(node -> node.getType().isProduction())
                .sorted(Comparator.comparingInt(this::priority)
                        .thenComparingLong(NodeRecord::getId))
                .toList();
    }

    private int priority(NodeRecord node) {
        if ("STORAGE_FULL".equals(node.getState())) {
            return 0;
        }
        var event = gui.explorationService().getEvent(node.getId());
        if (event != null && ("AVAILABLE".equals(event.getState())
                || "COMPLETED".equals(event.getState()))) {
            return 1;
        }
        if (gui.workerStore().getAssigned(node.getId()).isEmpty()) {
            return 2;
        }
        return 3;
    }

    private ItemStack iconFor(NodeRecord node) {
        Material material = switch (node.getType()) {
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.BEEF;
            case HUNTER -> Material.IRON_SWORD;
            case RESIDENTIAL -> Material.OAK_DOOR;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(Lang.line("menu.nodes.card.place", NamedTextColor.GRAY,
                "tier", node.getTier(),
                "x", node.getChunk().x(), "z", node.getChunk().z()));
        int crew = gui.workerStore().getAssigned(node.getId()).size();
        int capacity = bufferCapacity(node);
        lore.add(Ui.bar(Lang.get("menu.nodes.card.bar-buffer"), capacity == 0 ? 0
                        : node.storageTotal() / (double) capacity,
                node.storageTotal() >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                node.storageTotal() + "/" + capacity));
        lore.add(Ui.bar(Lang.get("menu.nodes.card.bar-crew"),
                node.getTier() == 0 ? 0 : crew / (double) node.getTier(),
                crew == 0 ? NamedTextColor.YELLOW : NamedTextColor.AQUA,
                crew + "/" + node.getTier()));
        if (node.bulkStorageTotal() > 0) {
            lore.add(Lang.line("menu.nodes.card.bulk", NamedTextColor.AQUA,
                    "count", node.bulkStorageTotal()));
        }
        lore.add(Lang.line("menu.nodes.card.exploration", NamedTextColor.LIGHT_PURPLE,
                "level", node.getExplorationLevel()));

        var event = gui.explorationService().getEvent(node.getId());
        NamedTextColor nameColor = NamedTextColor.GREEN;
        if ("STORAGE_FULL".equals(node.getState())) {
            lore.add(Ui.status(Lang.get("menu.nodes.state.collect"), NamedTextColor.RED));
            nameColor = NamedTextColor.RED;
        } else if (event != null && "COMPLETED".equals(event.getState())) {
            lore.add(Ui.status(Lang.get("menu.nodes.state.loot"), NamedTextColor.GOLD));
            nameColor = NamedTextColor.GOLD;
        } else if (event != null && "AVAILABLE".equals(event.getState())) {
            lore.add(Ui.status(Lang.get("menu.nodes.state.event"), NamedTextColor.GOLD));
            nameColor = NamedTextColor.GOLD;
        } else if (crew == 0) {
            lore.add(Ui.status(Lang.get("menu.nodes.state.no-crew"), NamedTextColor.YELLOW));
            nameColor = NamedTextColor.YELLOW;
        } else {
            lore.add(Ui.status(Lang.get("menu.nodes.state.active"), NamedTextColor.GREEN));
        }
        lore.add(Lang.click("menu.nodes.card.click"));
        return Icon.of(material)
                .name(Ui.pretty(node.getType().name()), nameColor)
                .loreComponents(lore).build();
    }

    private int bufferCapacity(NodeRecord node) {
        double multiplier = gui.gameDesignService() == null ? 1.0
                : gui.gameDesignService().bufferMultiplier(node);
        return (int) Math.round(gui.plugin().getConfig()
                .getInt("production.buffer-capacity-per-tier", 256)
                * node.getTier() * multiplier);
    }

    @Override
    protected Material frameMaterial() {
        return Material.GREEN_STAINED_GLASS_PANE;
    }
}
