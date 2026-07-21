package dev.branzx.idle.gui;

import dev.branzx.idle.service.TradeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Protected two-sided escrow view; offer mutations reset both confirmations. */
public final class TradeMenu extends Menu {

    private final GuiManager gui;

    public TradeMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.trade.title"), NamedTextColor.DARK_AQUA);
    }

    /** Row 2 is a solid divider so the two escrows can never be misread. */
    private static final int DIVIDER_ROW = 18;

    @Override
    protected void build() {
        TradeService.View view = gui.tradeService() == null ? null
                : gui.tradeService().view(viewer.getUniqueId());
        if (view == null) {
            set(SUMMARY_SLOT, Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.trade.none.name"), NamedTextColor.RED)
                    .lore(Lang.get("menu.trade.none.hint"), NamedTextColor.GRAY).build());
            navBar(Lang.get("menu.social.title"), () -> gui.openSocial(viewer));
            return;
        }
        String partner = Bukkit.getOfflinePlayer(view.partner()).getName();

        // Your offers above the divider, theirs below. Clicking one of yours
        // pulls it back out of escrow.
        for (int index = 0; index < view.mine().size() && index < 18; index++) {
            int offerIndex = index;
            set(index, view.mine().get(index), event -> {
                message(gui.tradeService().removeOffer(viewer, offerIndex));
                redraw();
            });
        }
        drawDivider(view, partner);
        for (int index = 0; index < view.theirs().size() && index < 18; index++) {
            set(27 + index, view.theirs().get(index));
        }

        set(navRow() + 1, Icon.of(Material.HOPPER)
                .name(Lang.get("menu.trade.offer.name"), NamedTextColor.YELLOW)
                .lore(Lang.get("menu.trade.offer.hint"), NamedTextColor.GRAY).build(),
                event -> {
                    message(gui.tradeService().offerHeld(viewer));
                    redraw();
                });
        setConfirm(navRow() + 2, Icon.of(Material.LIME_CONCRETE)
                .name(Lang.get("menu.trade.confirm.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.trade.confirm.hint", NamedTextColor.GRAY),
                        Lang.click("menu.trade.confirm.click")))
                .build(), () -> {
                    message(gui.tradeService().confirm(viewer));
                    if (gui.tradeService().view(viewer.getUniqueId()) == null) {
                        gui.openSocial(viewer);
                    } else {
                        redraw();
                    }
                });
        set(navRow() + 7, Icon.of(Material.RED_CONCRETE)
                .name(Lang.get("menu.trade.cancel.name"), NamedTextColor.RED)
                .lore(Lang.get("menu.trade.cancel.hint"), NamedTextColor.GRAY).build(),
                event -> {
                    message(gui.tradeService().cancel(viewer));
                    gui.openSocial(viewer);
                });
        navBar(Lang.get("menu.social.title"), () -> gui.openSocial(viewer));
    }

    /**
     * The one place glass panes survive the empty-background rule: this is a
     * structural divider between two players' property, and it doubles as the
     * confirmation readout for both sides.
     */
    private void drawDivider(TradeService.View view, String partner) {
        String mine = Lang.get(view.mineConfirmed()
                ? "menu.trade.divider.yours-ok" : "menu.trade.divider.yours");
        String theirs = Lang.get(view.theirsConfirmed()
                        ? "menu.trade.divider.theirs-ok" : "menu.trade.divider.theirs",
                "player", partner == null ? Lang.get("menu.trade.partner") : partner);
        for (int offset = 0; offset < 9; offset++) {
            boolean left = offset < 4;
            set(DIVIDER_ROW + offset, Icon.of(left
                            ? (view.mineConfirmed() ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.YELLOW_STAINED_GLASS_PANE)
                            : (view.theirsConfirmed() ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.RED_STAINED_GLASS_PANE))
                    .name(left ? mine : theirs,
                            left ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .build());
        }
    }

    private void message(TradeService.Result result) {
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    @Override
    protected Material frameMaterial() {
        return Material.BLUE_STAINED_GLASS_PANE;
    }
}
