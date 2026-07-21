package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Construction projects: your own settlement buildings and the server monument.
 *
 * <p>Contributing takes items out of the Warehouse for good, so every card here
 * is a three-step confirm. The two kinds sit on separate rows because they
 * spend from the same Warehouse but reward completely differently.
 */
public final class ProjectsMenu extends Menu {

    /** Your own projects on row 2, the shared monument alone on row 3. */
    private static final int[] PERSONAL = {19, 21, 23, 25};
    private static final int SERVER_SLOT = 31;

    private final GuiManager gui;

    public ProjectsMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.projects.title"), NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        GameDesignService design = gui.gameDesignService();
        if (design == null) {
            return;
        }
        List<GameDesignService.Project> projects = design.projects(viewer.getUniqueId());

        set(SUMMARY_SLOT, Icon.of(Material.SCAFFOLDING)
                .name(Lang.get("menu.projects.summary"), NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Lang.line("menu.projects.what", NamedTextColor.GRAY),
                        Lang.line("menu.projects.source", NamedTextColor.AQUA),
                        Lang.line("menu.projects.irreversible", NamedTextColor.RED)))
                .build());

        for (int index = 0; index < projects.size() && index < PERSONAL.length; index++) {
            GameDesignService.Project project = projects.get(index);
            setDangerConfirm(PERSONAL[index], card(project, false), () -> {
                message(design.contributeProject(viewer.getUniqueId(), project.id(), 64));
                redraw();
            });
        }
        if (projects.isEmpty()) {
            set(PERSONAL[0], Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.projects.none.name"), NamedTextColor.GRAY)
                    .lore(Lang.get("menu.projects.none.hint"), NamedTextColor.DARK_GRAY)
                    .build());
        }

        setDangerConfirm(SERVER_SLOT, card(design.serverProject(), true), () -> {
            message(design.contributeServerProject(viewer.getUniqueId(), 64));
            redraw();
        });

        navBar(Lang.get("menu.progress.tab.overview"), () -> gui.openProgress(viewer));
    }

    private org.bukkit.inventory.ItemStack card(GameDesignService.Project project,
                                                boolean server) {
        int stage = stageOf(project);
        return Icon.of(project.completed() ? Material.BEACON
                        : server ? Material.BELL : Material.SCAFFOLDING)
                .name(project.name(), project.completed() ? NamedTextColor.GREEN
                        : server ? NamedTextColor.YELLOW : NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Lang.line(server ? "menu.projects.card.server-what"
                                : "menu.projects.card.what", NamedTextColor.GRAY),
                        Lang.line("menu.projects.card.input", NamedTextColor.AQUA,
                                "material", ProgressMenu.pretty(project.material())),
                        Lang.line("menu.projects.card.stage", NamedTextColor.AQUA,
                                "stage", stage),
                        Ui.bar(Lang.get("menu.projects.card.bar"),
                                project.target() == 0 ? 0
                                        : project.current() / (double) project.target(),
                                server ? NamedTextColor.YELLOW : NamedTextColor.GOLD,
                                project.current() + "/" + project.target()),
                        project.completed()
                                ? Lang.line("menu.projects.card.done", NamedTextColor.GREEN)
                                : Lang.line("menu.projects.card.next", NamedTextColor.DARK_GRAY,
                                        "amount", nextThreshold(project)),
                        Lang.click("menu.projects.card.click")))
                .build();
    }

    /** Four visible construction stages, quarter by quarter. */
    static int stageOf(GameDesignService.Project project) {
        if (project.current() <= 0 || project.target() <= 0) {
            return 0;
        }
        double ratio = project.current() / (double) project.target();
        if (ratio >= 1.0) return 4;
        if (ratio >= 0.75) return 3;
        if (ratio >= 0.50) return 2;
        if (ratio >= 0.25) return 1;
        return 0;
    }

    private int nextThreshold(GameDesignService.Project project) {
        double ratio = switch (stageOf(project)) {
            case 0 -> 0.25;
            case 1 -> 0.50;
            case 2 -> 0.75;
            default -> 1.0;
        };
        return (int) Math.ceil(project.target() * ratio);
    }

    private void message(GameDesignService.Result result) {
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    @Override
    protected Material frameMaterial() {
        return Material.CYAN_STAINED_GLASS_PANE;
    }
}
