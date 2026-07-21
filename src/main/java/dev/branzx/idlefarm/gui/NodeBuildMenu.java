package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Specialization, refinement, mastery and four type-perk choices. */
public final class NodeBuildMenu extends Menu {

    private final GuiManager gui;
    private final long nodeId;

    public NodeBuildMenu(Player viewer, GuiManager gui, long nodeId) {
        super(viewer);
        this.gui = gui;
        this.nodeId = nodeId;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.nodebuild.title"), NamedTextColor.LIGHT_PURPLE);
    }

    private NodeRecord node() {
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(candidate -> candidate.getId() == nodeId).findFirst().orElse(null);
    }

    @Override
    protected void build() {
        NodeRecord node = node();
        GameDesignService design = gui.gameDesignService();
        if (node == null || design == null) {
            viewer.closeInventory();
            return;
        }
        set(4, Icon.of(Material.EXPERIENCE_BOTTLE)
                .name(node.getType() + " Lv." + node.getExplorationLevel(), NamedTextColor.AQUA)
                .lore("Build choices never remove Exploration EXP", NamedTextColor.GRAY).build());

        buildChoice(10, node, "specialization", "INDUSTRY", Material.PISTON,
                "Lv.25 • +15% quantity");
        buildChoice(11, node, "specialization", "DISCOVERY", Material.SPYGLASS,
                "Lv.25 • research and events");
        buildChoice(12, node, "specialization", "LOGISTICS", Material.CHEST,
                "Lv.25 • +50% buffer");

        String branch = design.specialization(node);
        if ("INDUSTRY".equals(branch)) {
            buildChoice(19, node, "refinement", "MASS_PRODUCTION", Material.HOPPER,
                    "Lv.50 • +10% common quantity");
            buildChoice(20, node, "refinement", "FINE_PROCESSING", Material.ANVIL,
                    "Lv.50 • advanced-resource weight");
        } else if ("DISCOVERY".equals(branch)) {
            buildChoice(19, node, "refinement", "DEEP_SURVEY", Material.COMPASS,
                    "Lv.50 • +15% event Node EXP");
            buildChoice(20, node, "refinement", "LUCKY_ROUTE", Material.RABBIT_FOOT,
                    "Lv.50 • extra rare event roll");
        } else if ("LOGISTICS".equals(branch)) {
            buildChoice(19, node, "refinement", "DEEP_STORAGE", Material.ENDER_CHEST,
                    "Lv.50 • another +50% buffer");
            buildChoice(20, node, "refinement", "SMART_ROUTING", Material.MINECART,
                    "Lv.50 • route commons to projects");
        }

        buildChoice(28, node, "mastery", "FOREMAN", Material.GOLDEN_HELMET,
                "Lv.75 • first 200 items/day quantity");
        buildChoice(29, node, "mastery", "PATHFINDER", Material.RECOVERY_COMPASS,
                "Lv.75 • first event offers choices");
        buildChoice(30, node, "mastery", "QUARTERMASTER", Material.BARREL,
                "Lv.75 • 50% research while full");

        int[] levels = {15, 35, 60, 85};
        for (int index = 0; index < levels.length; index++) {
            int level = levels[index];
            List<String> choices = design.typePerkChoices(node.getType(), level);
            int base = 37 + index * 2;
            set(base, Icon.of(node.getExplorationLevel() >= level ? Material.ENCHANTED_BOOK : Material.BOOK)
                    .name("Type Perk Lv." + level, node.getExplorationLevel() >= level
                            ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                    .loreComponents(choices.stream()
                            .map(choice -> Ui.line(pretty(choice), NamedTextColor.GRAY)).toList())
                    .build(), event -> {
                if (node.getExplorationLevel() < level) return;
                new AdminOptionMenu(viewer, "Choose Type Perk Lv." + level, choices,
                        selected -> applyTypePerk(node, level, selected),
                        this::open, true).open();
            });
        }

        navBar(Lang.get("menu.exploration.back"), () -> gui.openNodeDetail(viewer, node));
    }

    private void buildChoice(int slot, NodeRecord node, String tier, String choice,
                             Material material, String effect) {
        setDangerConfirm(slot, Icon.of(material)
                .name(pretty(choice), NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line(effect, NamedTextColor.GRAY),
                        Ui.line("Respecs may cost Coins and start a 7-day cooldown.",
                                NamedTextColor.DARK_GRAY),
                        Ui.click("choose this path"))).build(),
                () -> {
                    GameDesignService.Result result = gui.gameDesignService()
                            .selectBuild(viewer.getUniqueId(), node, tier, choice);
                    viewer.sendMessage(Component.text(result.message(),
                            result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    new NodeBuildMenu(viewer, gui, nodeId).open();
                });
    }

    private void applyTypePerk(NodeRecord node, int level, String choice) {
        GameDesignService.Result result = gui.gameDesignService()
                .selectTypePerk(viewer.getUniqueId(), node, level, choice);
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        new NodeBuildMenu(viewer, gui, nodeId).open();
    }

    private String pretty(String value) {
        String normalized = value.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    @Override
    protected Material frameMaterial() {
        return Material.GREEN_STAINED_GLASS_PANE;
    }
}
