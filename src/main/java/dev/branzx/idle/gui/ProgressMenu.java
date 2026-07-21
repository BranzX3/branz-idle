package dev.branzx.idle.gui;

import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Progression index.
 *
 * <p>This screen used to stack five unrelated systems — commissions, the weekly
 * chapter, achievements, projects and the discovery journal — into unlabelled
 * rows where identical-looking buttons did completely different things: one
 * claimed a reward, its neighbour rerolled and lost today's progress, and the
 * journal row could not be clicked at all. Each system now owns a screen, and
 * this page answers only two questions: what are these, and what can I do now.
 */
public final class ProgressMenu extends Menu {

    /** One card per system, centred on the same column as the summary. */
    private static final int COMMISSIONS_SLOT = 19;
    private static final int CHRONICLE_SLOT = 21;
    private static final int PROJECTS_SLOT = 23;
    private static final int JOURNAL_SLOT = 25;

    /** Read-outs and the one claim that has no screen of its own. */
    private static final int CHAPTER_SLOT = 29;
    private static final int SEASON_SLOT = 31;
    private static final int CREDITS_SLOT = 33;

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
        return Component.text(Lang.get("menu.progress.title"), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        GameDesignService design = gui.gameDesignService();
        if (design == null) {
            return;
        }
        tabBar(progressTabs(gui), 0);

        List<GameDesignService.Commission> commissions = design.commissions(viewer.getUniqueId());
        List<GameDesignService.Achievement> achievements =
                design.achievements(viewer.getUniqueId());
        long commissionsReady = commissions.stream()
                .filter(c -> c.current() >= c.target() && !c.claimed()).count();
        long achievementsReady = achievements.stream()
                .filter(a -> a.completed() && !a.claimed()).count();

        drawSummary(design, commissionsReady, achievementsReady);
        drawSectionCards(design, commissions, achievements,
                commissionsReady, achievementsReady);
        drawReadouts(design);
        navBarToHub(gui);
    }

    /** Answers "what can I do right now" before the player reads anything else. */
    private void drawSummary(GameDesignService design, long commissionsReady,
                             long achievementsReady) {
        long total = commissionsReady + achievementsReady;
        List<Component> lore = new ArrayList<>();
        if (total > 0) {
            lore.add(Lang.line("menu.progress.summary.ready", NamedTextColor.GOLD,
                    "count", total));
            if (commissionsReady > 0) {
                lore.add(Lang.line("menu.progress.summary.commissions", NamedTextColor.YELLOW,
                        "count", commissionsReady));
            }
            if (achievementsReady > 0) {
                lore.add(Lang.line("menu.progress.summary.achievements", NamedTextColor.AQUA,
                        "count", achievementsReady));
            }
        } else {
            lore.add(Lang.line("menu.progress.summary.nothing", NamedTextColor.GRAY));
        }

        NodeRecord focused = focusedNode(design);
        lore.add(focused == null
                ? Lang.line("menu.progress.summary.no-focus", NamedTextColor.DARK_GRAY)
                : Lang.line("menu.progress.summary.focus", NamedTextColor.DARK_GRAY,
                        "type", pretty(focused.getType().name()),
                        "level", focused.getExplorationLevel()));

        set(SUMMARY_SLOT, Icon.of(total > 0 ? Material.BELL : Material.WRITABLE_BOOK)
                .name(Lang.get("menu.progress.summary.name"),
                        total > 0 ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .loreComponents(lore).build());
    }

    /**
     * Four cards, one per system. Each says what it holds and what a click does,
     * so no card can be mistaken for another system's button.
     */
    private void drawSectionCards(GameDesignService design,
                                  List<GameDesignService.Commission> commissions,
                                  List<GameDesignService.Achievement> achievements,
                                  long commissionsReady, long achievementsReady) {
        set(COMMISSIONS_SLOT, Icon.of(commissionsReady > 0
                        ? Material.WRITABLE_BOOK : Material.PAPER)
                .name(Lang.get("menu.progress.card.commissions"),
                        commissionsReady > 0 ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Lang.line("menu.progress.card.commissions-what", NamedTextColor.GRAY),
                        Lang.line("menu.progress.card.count", NamedTextColor.AQUA,
                                "count", commissions.size()),
                        Lang.line("menu.progress.card.ready", NamedTextColor.GOLD,
                                "count", commissionsReady),
                        Lang.click("menu.progress.card.commissions-click")))
                .build(), event -> new CommissionsMenu(viewer, gui).open());

        set(CHRONICLE_SLOT, Icon.of(achievementsReady > 0
                        ? Material.FIREWORK_STAR : Material.WRITTEN_BOOK)
                .name(Lang.get("menu.progress.card.chronicle"),
                        achievementsReady > 0 ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.progress.card.chronicle-what", NamedTextColor.GRAY),
                        Lang.line("menu.progress.card.count", NamedTextColor.AQUA,
                                "count", achievements.size()),
                        Lang.line("menu.progress.card.ready", NamedTextColor.GOLD,
                                "count", achievementsReady),
                        Lang.click("menu.progress.card.chronicle-click")))
                .build(), event -> new ChronicleMenu(viewer, gui, 0).open());

        List<GameDesignService.Project> projects = design.projects(viewer.getUniqueId());
        GameDesignService.Project server = design.serverProject();
        set(PROJECTS_SLOT, Icon.of(Material.SCAFFOLDING)
                .name(Lang.get("menu.progress.card.projects"), NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Lang.line("menu.progress.card.projects-what", NamedTextColor.GRAY),
                        Lang.line("menu.progress.card.count", NamedTextColor.AQUA,
                                "count", projects.size()),
                        Lang.line("menu.progress.card.projects-server", NamedTextColor.YELLOW,
                                "stage", ProjectsMenu.stageOf(server)),
                        Lang.click("menu.progress.card.projects-click")))
                .build(), event -> new ProjectsMenu(viewer, gui).open());

        int discovered = 0;
        for (NodeType type : NodeType.values()) {
            if (type.isProduction()) {
                discovered += design.discoveries(viewer.getUniqueId(), type).size();
            }
        }
        set(JOURNAL_SLOT, Icon.of(Material.KNOWLEDGE_BOOK)
                .name(Lang.get("menu.progress.card.journal"), NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.progress.card.journal-what", NamedTextColor.GRAY),
                        Lang.line("menu.progress.card.journal-count", NamedTextColor.AQUA,
                                "count", discovered),
                        Lang.click("menu.progress.card.journal-click")))
                .build(), event -> new JournalMenu(viewer, gui, null).open());
    }

    private void drawReadouts(GameDesignService design) {
        var rewards = design.progressionRewards();
        setConfirm(CHAPTER_SLOT, Icon.of(Material.ENCHANTED_BOOK)
                .name(Lang.get("menu.progress.chapter.name"), NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Lang.line("menu.progress.chapter.what", NamedTextColor.GRAY),
                        Lang.line("menu.progress.chapter.reward", NamedTextColor.AQUA,
                                "exp", rewards.weeklyChapterExp(),
                                "coins", rewards.weeklyChapterCoins()),
                        Lang.click("menu.progress.chapter.click")))
                .build(), () -> {
                    message(design.claimWeeklyChapter(viewer.getUniqueId()));
                    redraw();
                });

        set(SEASON_SLOT, Icon.of(Material.CLOCK)
                .name(Lang.get("menu.progress.season.name", "season", design.seasonId()),
                        NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.progress.season.week", NamedTextColor.GRAY,
                                "week", design.seasonWeek(), "phase", design.seasonPhase()),
                        Lang.line("menu.progress.season.modifier", NamedTextColor.YELLOW,
                                "modifier", pretty(design.seasonModifier())),
                        Lang.line("menu.progress.season.points", NamedTextColor.LIGHT_PURPLE,
                                "points", design.chroniclePoints(viewer.getUniqueId()),
                                "seasonal", design.seasonalChroniclePoints(viewer.getUniqueId()))))
                .build());

        long credits = gui.creditService() == null ? 0
                : gui.creditService().balance(viewer.getUniqueId());
        set(CREDITS_SLOT, Icon.of(Material.AMETHYST_SHARD)
                .name(Lang.get("menu.progress.credits.name", "credits", credits),
                        NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.progress.credits.rules", NamedTextColor.GRAY),
                        Lang.line("menu.progress.credits.never", NamedTextColor.DARK_GRAY)))
                .build());
    }

    private NodeRecord focusedNode(GameDesignService design) {
        Long id = design.focusedNode(viewer.getUniqueId());
        if (id == null) {
            return null;
        }
        return gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(node -> node.getId() == id).findFirst().orElse(null);
    }

    private void message(GameDesignService.Result result) {
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    static String pretty(String value) {
        String normalized = value.toLowerCase().replace('_', ' ');
        return normalized.isEmpty() ? normalized
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    @Override
    protected Material frameMaterial() {
        return Material.CYAN_STAINED_GLASS_PANE;
    }
}
