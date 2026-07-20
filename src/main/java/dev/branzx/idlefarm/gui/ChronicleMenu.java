package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.GameDesignService;
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

    private static final int PAGE_SIZE = 45;
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
        return Component.text("Pioneer Chronicle — Achievements", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        fill();
        GameDesignService design = gui.gameDesignService();
        if (design == null) return;

        List<GameDesignService.Achievement> all =
                new ArrayList<>(design.achievements(viewer.getUniqueId()));
        // Claimable first inside each track so the next reward is obvious.
        all.sort(Comparator
                .comparingInt((GameDesignService.Achievement a) ->
                        Math.max(0, CATEGORY_ORDER.indexOf(a.category())))
                .thenComparing(a -> !(a.completed() && !a.claimed())));

        int start = page * PAGE_SIZE;
        List<GameDesignService.Achievement> shown =
                all.subList(Math.min(start, all.size()), Math.min(start + PAGE_SIZE, all.size()));
        int slot = 0;
        for (GameDesignService.Achievement achievement : shown) {
            Material material = achievement.claimed() ? Material.LIME_DYE
                    : achievement.completed() ? Material.FIREWORK_STAR : Material.GRAY_DYE;
            set(slot++, Icon.of(material)
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

        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("Previous", NamedTextColor.YELLOW).build(),
                    e -> new ChronicleMenu(viewer, gui, page - 1).open());
        }
        long claimable = all.stream().filter(a -> a.completed() && !a.claimed()).count();
        set(49, Icon.of(Material.EXPERIENCE_BOTTLE)
                .name("Chronicle Points: " + design.chroniclePoints(viewer.getUniqueId()),
                        NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line(claimable + " rewards ready to claim", NamedTextColor.GOLD),
                        Ui.line("Page " + (page + 1) + "/" + Math.max(1,
                                (all.size() + PAGE_SIZE - 1) / PAGE_SIZE), NamedTextColor.GRAY)))
                .build());
        set(48, Icon.of(Material.ARROW).name("กลับ Progress", NamedTextColor.GREEN)
                .lore("Back to Progress", NamedTextColor.DARK_GRAY).build(),
                e -> gui.openProgress(viewer));
        if (start + PAGE_SIZE < all.size()) {
            set(53, Icon.of(Material.ARROW).name("Next", NamedTextColor.YELLOW).build(),
                    e -> new ChronicleMenu(viewer, gui, page + 1).open());
        }
    }

    private String pretty(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return normalized.isEmpty() ? normalized
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
