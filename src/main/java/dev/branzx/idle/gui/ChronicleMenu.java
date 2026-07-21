package dev.branzx.idle.gui;

import dev.branzx.idle.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Paginated Pioneer Chronicle: every visible achievement grouped by track. */
public final class ChronicleMenu extends Menu {

    private static final List<String> CATEGORY_ORDER = List.of(
            "JOURNEY", "MASTERY", "DISCOVERY", "WORKER",
            "TERRITORY", "EXPEDITION", "SOCIAL", "FEAT");

    private final GuiManager gui;
    private final int page;

    public ChronicleMenu(Player viewer, GuiManager gui, int page) {
        super(viewer);
        this.gui = gui;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.progress.tab.chronicle"), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        GameDesignService design = gui.gameDesignService();
        if (design == null) return;

        List<GameDesignService.Achievement> all =
                new ArrayList<>(design.achievements(viewer.getUniqueId()));
        // Claimable first inside each track so the next reward is obvious.
        all.sort(Comparator
                .comparingInt((GameDesignService.Achievement a) ->
                        Math.max(0, CATEGORY_ORDER.indexOf(a.category())))
                .thenComparing(a -> !(a.completed() && !a.claimed())));

        tabBar(progressTabs(gui), 1);

        long claimable = all.stream().filter(a -> a.completed() && !a.claimed()).count();
        set(SUMMARY_SLOT, Icon.of(Material.EXPERIENCE_BOTTLE)
                .name(Lang.get("menu.chronicle.points", "points",
                        design.chroniclePoints(viewer.getUniqueId())), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.chronicle.ready", NamedTextColor.GOLD,
                                "count", claimable),
                        Lang.line("menu.chronicle.total", NamedTextColor.GRAY,
                                "count", all.size())))
                .build());

        int start = page * CONTENT_GRID.length;
        List<GameDesignService.Achievement> shown = all.subList(Math.min(start, all.size()),
                Math.min(start + CONTENT_GRID.length, all.size()));
        int slot = 0;
        for (GameDesignService.Achievement achievement : shown) {
            Material material = achievement.claimed() ? Material.LIME_DYE
                    : achievement.completed() ? Material.FIREWORK_STAR : Material.GRAY_DYE;
            set(CONTENT_GRID[slot++], Icon.of(material)
                    .name(achievement.name(), achievement.completed()
                            ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .loreComponents(List.of(
                            Ui.line(pretty(achievement.category()) + " track", NamedTextColor.LIGHT_PURPLE),
                            Ui.line(achievement.description(), NamedTextColor.GRAY),
                            Ui.line("+" + achievement.points() + " Chronicle Points", NamedTextColor.AQUA),
                            Ui.line(achievement.claimed() ? "Claimed"
                                            : achievement.completed() ? "Click to claim" : "Incomplete",
                                    NamedTextColor.DARK_GRAY))).build(), event -> {
                var result = design.claimAchievement(viewer.getUniqueId(), achievement.id());
                viewer.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                redraw();
            });
        }

        navBar(Lang.get("menu.progress.tab.overview"), () -> gui.openProgress(viewer));
        pager(page, pageCount(all.size()),
                target -> new ChronicleMenu(viewer, gui, target).open());
    }

    private String pretty(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return normalized.isEmpty() ? normalized
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    @Override
    protected Material frameMaterial() {
        return Material.CYAN_STAINED_GLASS_PANE;
    }
}
