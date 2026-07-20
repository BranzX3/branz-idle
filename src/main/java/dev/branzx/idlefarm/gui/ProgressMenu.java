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
        fill();
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
                                    Ui.line(commission.claimed() ? "Claimed"
                                                    : done ? "Claim reward"
                                                    : "Review today's free reroll",
                                            commission.claimed() ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_GRAY)))
                            .build(),
                    event -> {
                        if (commission.claimed()) return;
                        if (done) {
                            message(design.claimCommission(viewer.getUniqueId(), commission.id()));
                            redraw();
                        } else {
                            new ConfirmMenu(viewer, "Use free reroll?",
                                    List.of(commission.description(),
                                            "Current progress will be replaced."),
                                    () -> {
                                        message(design.rerollCommission(
                                                viewer.getUniqueId(), commission.id()));
                                        open();
                                    }, this::open).open();
                        }
                    });
        }
        var rewardCatalog = design.progressionRewards();
        set(16, Icon.of(Material.ENCHANTED_BOOK).name("Weekly Node Chapter", NamedTextColor.GOLD)
                .lore("5 active days → " + rewardCatalog.weeklyChapterExp()
                                + " Node EXP + " + rewardCatalog.weeklyChapterCoins() + " Coins",
                        NamedTextColor.GRAY).build(),
                event -> {
                    GameDesignService.Result result = design.claimWeeklyChapter(viewer.getUniqueId());
                    message(result);
                    redraw();
                });

        // Highlight reel: claimable first, then nearest goals; the full
        // paginated Chronicle lives in its own menu.
        List<GameDesignService.Achievement> achievements =
                new ArrayList<>(design.achievements(viewer.getUniqueId()));
        achievements.sort(java.util.Comparator
                .comparing((GameDesignService.Achievement a) -> !(a.completed() && !a.claimed()))
                .thenComparing(GameDesignService.Achievement::claimed));
        int achievementSlot = 18;
        for (GameDesignService.Achievement achievement : achievements) {
            if (achievementSlot >= 26) break;
            Material material = achievement.claimed() ? Material.LIME_DYE
                    : achievement.completed() ? Material.FIREWORK_STAR : Material.GRAY_DYE;
            set(achievementSlot++, Icon.of(material)
                    .name(achievement.name(), achievement.completed()
                            ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .loreComponents(List.of(
                            Ui.line(pretty(achievement.category()) + " track", NamedTextColor.LIGHT_PURPLE),
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
        long claimable = achievements.stream().filter(a -> a.completed() && !a.claimed()).count();
        set(26, Icon.of(Material.WRITTEN_BOOK).name("Full Chronicle", NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.line(achievements.size() + " achievements across all tracks",
                                NamedTextColor.GRAY),
                        Ui.line(claimable + " ready to claim", NamedTextColor.AQUA),
                        Ui.line("Click to browse", NamedTextColor.DARK_GRAY))).build(),
                event -> new ChronicleMenu(viewer, gui, 0).open());

        int projectSlot = 27;
        for (GameDesignService.Project project : design.projects(viewer.getUniqueId())) {
            set(projectSlot++, Icon.of(project.completed() ? Material.BEACON : Material.SCAFFOLDING)
                    .name(project.name(), project.completed() ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                    .loreComponents(List.of(
                            Ui.line("Input: " + pretty(project.material()), NamedTextColor.GRAY),
                            Ui.line("World construction: Stage " + projectStage(project) + "/4",
                                    NamedTextColor.AQUA),
                            Ui.bar("Construction", project.current() / (double) project.target(),
                                    NamedTextColor.GOLD, project.current() + "/" + project.target()),
                            Ui.line(project.completed() ? "Completed beside your Residential Node"
                                            : "Next stage at " + nextProjectThreshold(project)
                                                    + " • click to contribute up to 64",
                                    NamedTextColor.DARK_GRAY))).build(),
                    event -> confirmProjectContribution(project));
        }
        GameDesignService.Project serverProject = design.serverProject();
        set(32, Icon.of(serverProject.completed() ? Material.BEACON : Material.BELL)
                .name(serverProject.name(), NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line("Server-wide seven-day resource sink", NamedTextColor.GRAY),
                        Ui.line("World monument: Stage " + projectStage(serverProject) + "/4",
                                NamedTextColor.AQUA),
                        Ui.bar("Community", serverProject.current() / (double) serverProject.target(),
                                NamedTextColor.YELLOW,
                                serverProject.current() + "/" + serverProject.target()),
                        Ui.line("Click: contribute up to 64 " + pretty(serverProject.material()),
                                NamedTextColor.DARK_GRAY))).build(),
                event -> confirmServerContribution(serverProject));

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
        backToHub(gui);
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

    private void confirmProjectContribution(GameDesignService.Project project) {
        new ConfirmMenu(viewer, "Contribute to " + project.name() + "?",
                List.of("Consumes up to 64 " + pretty(project.material()),
                        "Items are taken from Warehouse",
                        "Contribution cannot be withdrawn"),
                () -> {
                    message(gui.gameDesignService().contributeProject(
                            viewer.getUniqueId(), project.id(), 64));
                    new ProgressMenu(viewer, gui).open();
                },
                () -> new ProgressMenu(viewer, gui).open()).open();
    }

    private void confirmServerContribution(GameDesignService.Project project) {
        new ConfirmMenu(viewer, "Contribute to the Server Project?",
                List.of("Consumes up to 64 " + pretty(project.material()),
                        "Items are taken from Warehouse",
                        "Contribution cannot be withdrawn"),
                () -> {
                    message(gui.gameDesignService().contributeServerProject(
                            viewer.getUniqueId(), 64));
                    new ProgressMenu(viewer, gui).open();
                },
                () -> new ProgressMenu(viewer, gui).open()).open();
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

    private int projectStage(GameDesignService.Project project) {
        if (project.current() <= 0 || project.target() <= 0) return 0;
        double ratio = project.current() / (double) project.target();
        if (ratio >= 1.0) return 4;
        if (ratio >= 0.75) return 3;
        if (ratio >= 0.50) return 2;
        if (ratio >= 0.25) return 1;
        return 0;
    }

    private int nextProjectThreshold(GameDesignService.Project project) {
        int stage = projectStage(project);
        double ratio = switch (stage) {
            case 0 -> 0.25;
            case 1 -> 0.50;
            case 2 -> 0.75;
            default -> 1.0;
        };
        return (int) Math.ceil(project.target() * ratio);
    }

    private String pretty(String value) {
        String normalized = value.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
