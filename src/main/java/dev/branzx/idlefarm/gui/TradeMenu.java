package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.TradeService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

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
        return Component.text("Protected Trade Escrow", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        for (int slot = 0; slot < 54; slot++) set(slot, Icon.filler());
        TradeService.View view = gui.tradeService() == null ? null
                : gui.tradeService().view(viewer.getUniqueId());
        if (view == null) {
            set(22, Icon.of(Material.BARRIER).name("No active trade", NamedTextColor.RED)
                    .lore("Use /idle trade <player>", NamedTextColor.GRAY).build());
            backButton(49, "Social", () -> gui.openSocial(viewer));
            return;
        }
        String partner = Bukkit.getOfflinePlayer(view.partner()).getName();
        set(18, Icon.of(Material.LIME_STAINED_GLASS_PANE)
                .name("Your escrow" + (view.mineConfirmed() ? " ✓" : ""), NamedTextColor.GREEN).build());
        set(26, Icon.of(Material.RED_STAINED_GLASS_PANE)
                .name((partner == null ? "Partner" : partner) + " escrow"
                        + (view.theirsConfirmed() ? " ✓" : ""), NamedTextColor.RED).build());
        for (int index = 0; index < view.mine().size() && index < 18; index++) {
            int offerIndex = index;
            set(index, view.mine().get(index), event -> {
                message(gui.tradeService().removeOffer(viewer, offerIndex));
                redraw();
            });
        }
        for (int index = 0; index < view.theirs().size() && index < 18; index++) {
            set(27 + index, view.theirs().get(index));
        }
        set(47, Icon.of(Material.HOPPER).name("Offer Held Stack", NamedTextColor.YELLOW)
                .lore("Any change resets both confirmations", NamedTextColor.GRAY).build(), event -> {
            message(gui.tradeService().offerHeld(viewer));
            redraw();
        });
        set(49, Icon.of(Material.LIME_CONCRETE).name("Confirm", NamedTextColor.GREEN)
                .lore("Both players must confirm unchanged offers", NamedTextColor.GRAY).build(), event -> {
            message(gui.tradeService().confirm(viewer));
            if (gui.tradeService().view(viewer.getUniqueId()) == null) gui.openSocial(viewer);
            else redraw();
        });
        set(51, Icon.of(Material.RED_CONCRETE).name("Cancel & Return Escrow", NamedTextColor.RED)
                .build(), event -> {
            message(gui.tradeService().cancel(viewer));
            gui.openSocial(viewer);
        });
    }

    private void message(TradeService.Result result) {
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }
}
