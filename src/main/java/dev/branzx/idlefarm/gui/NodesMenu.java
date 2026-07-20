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

    private static final int PAGE_SIZE = 27;

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
        return Component.text("IdleFarm | Node Control", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        fill();
        List<NodeRecord> nodes = sortedProductionNodes();
        long attention = nodes.stream().filter(node -> priority(node) < 3).count();

        set(4, Icon.of(attention > 0 ? Material.BELL : Material.LIME_DYE)
                .name(nodes.size() + " Production Nodes",
                        attention > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(attention > 0
                                        ? attention + " need attention"
                                        : "All Nodes are running normally",
                                attention > 0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN),
                        Ui.line("Nodes needing action are shown first", NamedTextColor.GRAY)))
                .build());

        int start = page * PAGE_SIZE;
        for (int index = 0; index < PAGE_SIZE && start + index < nodes.size(); index++) {
            NodeRecord node = nodes.get(start + index);
            set(9 + index, iconFor(node), event -> gui.openNodeDetail(viewer, node));
        }

        if (nodes.isEmpty()) {
            set(22, Icon.of(Material.COMPASS)
                    .name("Build your first Production Node", NamedTextColor.YELLOW)
                    .loreComponents(List.of(
                            Ui.line("Claim a chunk next to your Residential plot",
                                    NamedTextColor.GRAY),
                            Ui.click("open Territory Map")))
                    .build(), event -> gui.openTerritoryMap(viewer));
        }

        set(45, Icon.of(Material.FILLED_MAP)
                .name("Territory Map", NamedTextColor.GREEN)
                .lore("Claim land or inspect nearby chunks", NamedTextColor.GRAY)
                .build(), event -> gui.openTerritoryMap(viewer));
        if (page > 0) {
            set(47, Icon.of(Material.ARROW)
                    .name("Previous Page", NamedTextColor.YELLOW).build(),
                    event -> new NodesMenu(viewer, gui, page - 1).open());
        }
        set(49, Icon.of(Material.NETHER_STAR)
                .name("Back to Hub", NamedTextColor.GREEN).build(),
                event -> gui.openMainHub(viewer));
        if (start + PAGE_SIZE < nodes.size()) {
            set(51, Icon.of(Material.ARROW)
                    .name("Next Page", NamedTextColor.YELLOW).build(),
                    event -> new NodesMenu(viewer, gui, page + 1).open());
        }
        set(53, Icon.of(Material.CLOCK)
                .name("Refresh", NamedTextColor.AQUA)
                .lore("Update every Node status", NamedTextColor.GRAY)
                .build(), event -> redraw());
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
        lore.add(Ui.line("Tier " + node.getTier() + " | Chunk "
                + node.getChunk().x() + ", " + node.getChunk().z(), NamedTextColor.GRAY));
        int crew = gui.workerStore().getAssigned(node.getId()).size();
        lore.add(Ui.line("Crew: " + crew + "/" + node.getTier(), NamedTextColor.AQUA));
        int capacity = bufferCapacity(node);
        lore.add(Ui.bar("Buffer", capacity == 0 ? 0
                        : node.storageTotal() / (double) capacity,
                node.storageTotal() >= capacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                node.storageTotal() + "/" + capacity));
        lore.add(Ui.line("Exploration Lv." + node.getExplorationLevel(),
                NamedTextColor.LIGHT_PURPLE));

        var event = gui.explorationService().getEvent(node.getId());
        NamedTextColor nameColor = NamedTextColor.GREEN;
        if ("STORAGE_FULL".equals(node.getState())) {
            lore.add(Ui.status("COLLECT BUFFER", NamedTextColor.RED));
            nameColor = NamedTextColor.RED;
        } else if (event != null && "COMPLETED".equals(event.getState())) {
            lore.add(Ui.status("LOOT READY", NamedTextColor.GOLD));
            nameColor = NamedTextColor.GOLD;
        } else if (event != null && "AVAILABLE".equals(event.getState())) {
            lore.add(Ui.status("EVENT READY", NamedTextColor.GOLD));
            nameColor = NamedTextColor.GOLD;
        } else if (crew == 0) {
            lore.add(Ui.status("ASSIGN A WORKER", NamedTextColor.YELLOW));
            nameColor = NamedTextColor.YELLOW;
        } else {
            lore.add(Ui.status("ACTIVE", NamedTextColor.GREEN));
        }
        lore.add(Ui.click("open Node Control"));
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
}
