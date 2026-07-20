package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Unified Journal, Commissions, Chronicle, Projects and season surface. */
public final class ProgressMenu extends Menu {

    private final GuiManager gui;

    public ProgressMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Pioneer Chronicle", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) set(i, Icon.filler());
        GameDesignService design = gui.gameDesignService();
        if (design == null) return;

        Long focusedId = design.focusedNode(viewer.getUniqueId());
        NodeRecord focused = focusedId == null ? null : gui.nodeStore().getByOwner(viewer.getUniqueId())
                .stream().filter(node -> node.getId() == focusedId).findFirst().orElse(null);
        if (focused == null) {
            set(4, Icon.of(Material.COMPASS).name("No Focused Node", NamedTextColor.YELLOW)
                    .lore("Open a Production Node and select Focus", NamedTextColor.GRAY).build(),
                    event -> gui.openNodes(viewer));
        } else {
            set(4, Icon.of(Material.COMPASS).name("Focused: " + pretty(focused.getType().name()),
                            NamedTextColor.AQUA)
                    .loreComponents(List.of(
                            Ui.line("Exploration Lv." + focused.getExplorationLevel(), NamedTextColor.LIGHT_PURPLE),
                            Ui.line("Tier " + focused.getTier(), NamedTextColor.GRAY),
                            Ui.line("Click: open node", NamedTextColor.DARK_GRAY))).build(),
                    event -> gui.openNodeDetail(viewer, focused));
        }

        int commissionSlot = 9;
        for (GameDesignService.Commission commission : design.commissions(viewer.getUniqueId())) {
            boolean done = commission.current() >= commission.target();
            Material icon = commission.claimed() ? Material.LIME_DYE
                    : done ? Material.WRITABLE_BOOK : Material.PAPER;
            set(commissionSlot++, Icon.of(icon)
                            .name("Daily: " + pretty(commission.id()),
                                    commission.claimed() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                            .loreComponents(List.of(
                                    Ui.line(commission.description(), NamedTextColor.GRAY),
                                    Ui.bar("Progress", commission.current() / (double) commission.target(),
                                            done ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                                            commission.current() + "/" + commission.target()),
                                    Ui.line("Reward: " + commission.reward(), NamedTextColor.GOLD),
                                    Ui.line(commission.claimed() ? "Claimed" : done ? "Click to claim" : "In progress",
                                            commission.claimed() ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_GRAY)))
                            .build(),
                    event -> {
                        GameDesignService.Result result =
                                design.claimCommission(viewer.getUniqueId(), commission.id());
                        message(result);
                        redraw();
                    });
        }
        set(16, Icon.of(Material.ENCHANTED_BOOK).name("Weekly Node Chapter", NamedTextColor.GOLD)
                .lore("5 daily actions → 3,500 Node EXP + 2,000 Coins", NamedTextColor.GRAY).build(),
                event -> {
                    GameDesignService.Result result = design.claimWeeklyChapter(viewer.getUniqueId());
                    message(result);
                    redraw();
                });

        int achievementSlot = 18;
        for (GameDesignService.Achievement achievement : design.achievements(viewer.getUniqueId())) {
            Material material = achievement.claimed() ? Material.LIME_DYE
                    : achievement.completed() ? Material.FIREWORK_STAR : Material.GRAY_DYE;
            set(achievementSlot++, Icon.of(material)
                    .name(achievement.name(), achievement.completed()
                            ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .loreComponents(List.of(
                            Ui.line(achievement.description(), NamedTextColor.GRAY),
                            Ui.line("+" + achievement.points() + " Chronicle Points", NamedTextColor.AQUA),
                            Ui.line(achievement.claimed() ? "Claimed"
                                            : achievement.completed() ? "Click to claim" : "Incomplete",
                                    NamedTextColor.DARK_GRAY))).build(), event -> {
                GameDesignService.Result result =
                        design.claimAchievement(viewer.getUniqueId(), achievement.id());
                message(result);
                redraw();
            });
        }

        int projectSlot = 27;
        for (GameDesignService.Project project : design.projects(viewer.getUniqueId())) {
            set(projectSlot++, Icon.of(project.completed() ? Material.BEACON : Material.SCAFFOLDING)
                    .name(project.name(), project.completed() ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Input: " + pretty(project.material()), NamedTextColor.GRAY),
                            Ui.bar("Construction", project.current() / (double) project.target(),
                                    NamedTextColor.GOLD, project.current() + "/" + project.target()),
                            Ui.line(project.completed() ? "Completed"
                                            : "Click: contribute up to 64 from Warehouse",
                                    NamedTextColor.DARK_GRAY))).build(), event -> {
                GameDesignService.Result result =
                        design.contributeProject(viewer.getUniqueId(), project.id(), 64);
                message(result);
                redraw();
            });
        }
        GameDesignService.Project serverProject = design.serverProject();
        set(32, Icon.of(serverProject.completed() ? Material.BEACON : Material.BELL)
                .name(serverProject.name(), NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line("Server-wide seven-day resource sink", NamedTextColor.GRAY),
                        Ui.bar("Community", serverProject.current() / (double) serverProject.target(),
                                NamedTextColor.YELLOW,
                                serverProject.current() + "/" + serverProject.target()),
                        Ui.line("Click: contribute up to 64 " + pretty(serverProject.material()),
                                NamedTextColor.DARK_GRAY))).build(), event -> {
            GameDesignService.Result result =
                    design.contributeServerProject(viewer.getUniqueId(), 64);
            message(result);
            redraw();
        });

        int journalSlot = 36;
        for (NodeType type : NodeType.values()) {
            if (!type.isProduction()) continue;
            var entries = design.discoveries(viewer.getUniqueId(), type);
            List<Component> lore = new ArrayList<>();
            lore.add(Ui.line(entries.size() + " resources discovered", NamedTextColor.AQUA));
            entries.entrySet().stream().limit(4).forEach(entry ->
                    lore.add(Ui.line("• " + pretty(entry.getKey()) + " ×" + entry.getValue(),
                            NamedTextColor.GRAY)));
            lore.add(Ui.line("Undiscovered entries remain silhouettes", NamedTextColor.DARK_GRAY));
            set(journalSlot++, Icon.of(icon(type)).name(pretty(type.name()) + " Journal",
                            NamedTextColor.LIGHT_PURPLE)
                    .loreComponents(lore).build());
        }

        long credits = gui.creditService() == null ? 0
                : gui.creditService().balance(viewer.getUniqueId());
        set(46, Icon.of(Material.AMETHYST_SHARD).name("Credits", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line(credits + " Credits", NamedTextColor.GOLD),
                        Ui.line("Non-transferable • no cash-out", NamedTextColor.GRAY),
                        Ui.line("Never buys EXP, RNG, or tradable power", NamedTextColor.DARK_GRAY))).build());
        set(48, Icon.of(Material.CLOCK).name("Season " + design.seasonId(), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("Week " + design.seasonWeek() + "/12 • " + design.seasonPhase(),
                                NamedTextColor.GRAY),
                        Ui.line("Modifier: " + pretty(design.seasonModifier()), NamedTextColor.YELLOW)))
                .build());
        set(49, Icon.of(Material.NETHER_STAR).name("Main Hub", NamedTextColor.GREEN).build(),
                event -> gui.openMainHub(viewer));
        set(52, Icon.of(Material.EXPERIENCE_BOTTLE)
                .name("Chronicle Points: " + design.chroniclePoints(viewer.getUniqueId()),
                        NamedTextColor.AQUA)
                .lore("Season Points: " + design.seasonalChroniclePoints(viewer.getUniqueId()),
                        NamedTextColor.LIGHT_PURPLE).build());
    }

    private void message(GameDesignService.Result result) {
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private Material icon(NodeType type) {
        return switch (type) {
            case MINING -> Material.IRON_PICKAXE;
            case FARMING -> Material.WHEAT;
            case WOODCUTTING -> Material.OAK_LOG;
            case LIVESTOCK -> Material.LEATHER;
            case HUNTER -> Material.BOW;
            default -> Material.BOOK;
        };
    }

    private String pretty(String value) {
        String normalized = value.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
