package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.PerkService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Social landing page so Trust, Trade, Visit and rankings form one flow. */
public final class SocialMenu extends Menu {

    private final GuiManager gui;

    public SocialMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm | Social", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        fill();
        int trusted = gui.nodeStore().getTrustedOf(viewer.getUniqueId()).size();
        set(4, Icon.head(gui.skinHeadCache(), viewer.getName())
                .name("Social & Territory Access", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(trusted + " trusted player(s)", NamedTextColor.AQUA),
                        Ui.line("Visits never grant build access", NamedTextColor.GRAY)))
                .build());

        set(11, Icon.of(Material.OAK_HANGING_SIGN)
                .name("Trusted Players", NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("Visitor, Helper and Manager roles", NamedTextColor.GRAY),
                        Ui.click("manage access")))
                .build(), event -> gui.openTrust(viewer));

        set(13, Icon.of(Material.CHEST)
                .name("Protected Trade", NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.line("Two-sided escrow with confirmation", NamedTextColor.GRAY),
                        Ui.line("Start with /idle trade <player>", NamedTextColor.DARK_GRAY),
                        Ui.click("open active trade")))
                .build(), event -> gui.openTrade(viewer));

        boolean closed = gui.perkService() != null
                && gui.perkService().has(viewer.getUniqueId(), PerkService.NO_VISITS);
        set(15, Icon.of(closed ? Material.IRON_DOOR : Material.OAK_DOOR)
                .name(closed ? "Visits: Closed" : "Visits: Open",
                        closed ? NamedTextColor.RED : NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line("Controls public visits to your home", NamedTextColor.GRAY),
                        Ui.click("toggle visit privacy")))
                .build(), event -> {
                    viewer.performCommand("idle visit toggle");
                    redraw();
                });

        set(22, Icon.of(Material.GOLD_INGOT)
                .name("Leaderboard", NamedTextColor.GOLD)
                .lore("Compare server progress", NamedTextColor.GRAY)
                .build(), event -> gui.openLeaderboard(viewer));

        backToHub(gui);
    }
}
