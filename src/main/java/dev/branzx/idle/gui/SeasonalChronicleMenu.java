package dev.branzx.idle.gui;

import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.service.design.SeasonalChronicleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Current-season objectives and non-power reward track. */
public final class SeasonalChronicleMenu extends Menu {

    private final GuiManager gui;

    public SeasonalChronicleMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override protected int rows() { return 6; }

    @Override protected Component title() {
        return Component.text(Lang.get("menu.progress.tab.seasonal"), NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        GameDesignService design = gui.gameDesignService();
        if (design == null) return;
        var schedule = design.seasonSchedule();
        tabBar(progressTabs(gui), 2);
        set(SUMMARY_SLOT, Icon.of(Material.CLOCK).name(design.seasonId(), NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.line("Week " + schedule.week() + " • " + schedule.phase(),
                                NamedTextColor.YELLOW),
                        Ui.line("Modifier: " + Ui.pretty(schedule.modifier()), NamedTextColor.AQUA),
                        Ui.line("Seasonal Points: "
                                + design.seasonalChroniclePoints(viewer.getUniqueId()),
                                NamedTextColor.LIGHT_PURPLE),
                        Ui.line("Older objectives remain recoverable", NamedTextColor.GRAY)))
                .build());

        int slot = 9;
        for (SeasonalChronicleService.Objective objective :
                design.seasonalObjectives(viewer.getUniqueId())) {
            if (slot >= 36) break;
            Material icon = objective.claimed() ? Material.LIME_DYE
                    : objective.completed() ? Material.FIREWORK_STAR
                    : objective.catchUp() ? Material.CLOCK : Material.PAPER;
            NamedTextColor color = objective.completed()
                    ? NamedTextColor.GREEN : objective.catchUp()
                    ? NamedTextColor.YELLOW : NamedTextColor.WHITE;
            set(slot++, Icon.of(icon).name("W" + objective.week() + " • " + objective.name(), color)
                    .loreComponents(List.of(
                            Ui.line(objective.description(), NamedTextColor.GRAY),
                            Ui.line(objective.current() + "/" + objective.target(),
                                    NamedTextColor.AQUA),
                            Ui.line("+" + objective.points() + " Seasonal Points",
                                    NamedTextColor.LIGHT_PURPLE),
                            Ui.line(objective.reward(), NamedTextColor.GOLD),
                            Ui.line(objective.claimed() ? "Claimed"
                                            : objective.completed() ? "Click to claim"
                                            : objective.catchUp() ? "Catch-up active" : "In progress",
                                    NamedTextColor.DARK_GRAY))).build(), event -> {
                var result = design.claimSeasonalObjective(viewer.getUniqueId(), objective.id());
                viewer.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                redraw();
            });
        }

        slot = 36;
        for (SeasonalChronicleService.RewardTier tier :
                design.seasonalRewardTrack(viewer.getUniqueId())) {
            if (slot >= 45) break;
            Material icon = tier.claimed() ? Material.NETHER_STAR
                    : tier.unlocked() ? Material.CHEST : Material.ENDER_CHEST;
            set(slot++, Icon.of(icon).name(tier.name(),
                            tier.unlocked() ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                    .loreComponents(List.of(
                            Ui.line("Requires " + tier.points() + " Seasonal Points",
                                    NamedTextColor.LIGHT_PURPLE),
                            Ui.line(tier.reward(), NamedTextColor.YELLOW),
                            Ui.line(tier.claimed() ? "Claimed"
                                            : tier.unlocked() ? "Click to claim" : "Locked",
                                    NamedTextColor.DARK_GRAY))).build(), event -> {
                var result = design.claimSeasonalReward(viewer.getUniqueId(), tier.id());
                viewer.sendMessage(Component.text(result.message(),
                        result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
                redraw();
            });
        }
        navBar(Lang.get("menu.progress.tab.chronicle"),
                () -> new ChronicleMenu(viewer, gui, 0).open());
    }

    @Override
    protected Material frameMaterial() {
        return Material.CYAN_STAINED_GLASS_PANE;
    }
}
