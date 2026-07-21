package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.GameDesignService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Daily commissions.
 *
 * <p>Split out of the Progress index because a commission card carries two very
 * different clicks depending on its state — claiming a finished one is free,
 * rerolling an unfinished one throws today's progress away. On its own screen
 * each card can say which of the two it is, and the reroll keeps its
 * second-click gate.
 */
public final class CommissionsMenu extends Menu {

    private final GuiManager gui;

    public CommissionsMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.commissions.title"), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        GameDesignService design = gui.gameDesignService();
        if (design == null) {
            return;
        }
        List<GameDesignService.Commission> commissions = design.commissions(viewer.getUniqueId());
        long ready = commissions.stream()
                .filter(c -> c.current() >= c.target() && !c.claimed()).count();

        set(SUMMARY_SLOT, Icon.of(ready > 0 ? Material.BELL : Material.PAPER)
                .name(Lang.get("menu.commissions.summary", "count", commissions.size()),
                        ready > 0 ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Lang.line("menu.commissions.what", NamedTextColor.GRAY),
                        Lang.line("menu.commissions.ready", NamedTextColor.GOLD,
                                "count", ready),
                        Lang.line("menu.commissions.reroll-note", NamedTextColor.DARK_GRAY)))
                .build());

        int index = 0;
        for (GameDesignService.Commission commission : commissions) {
            if (index >= CONTENT_GRID.length) {
                break;
            }
            draw(CONTENT_GRID[index++], design, commission);
        }

        navBar(Lang.get("menu.progress.tab.overview"), () -> gui.openProgress(viewer));
    }

    private void draw(int slot, GameDesignService design,
                      GameDesignService.Commission commission) {
        boolean done = commission.current() >= commission.target();
        Material material = commission.claimed() ? Material.LIME_DYE
                : done ? Material.WRITABLE_BOOK : Material.PAPER;

        // The last lore line is the affordance: it names the one thing this
        // card's click will do, in this exact state.
        String actionKey = commission.claimed() ? "menu.commissions.state.claimed"
                : done ? "menu.commissions.state.claim" : "menu.commissions.state.reroll";
        ItemStack card = Icon.of(material)
                .name(Lang.get("menu.progress.commission.name",
                                "id", ProgressMenu.pretty(commission.id())),
                        commission.claimed() ? NamedTextColor.GREEN
                                : done ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line(commission.description(), NamedTextColor.GRAY),
                        Ui.bar(Lang.get("menu.progress.commission.bar"),
                                commission.target() == 0 ? 0
                                        : commission.current() / (double) commission.target(),
                                done ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                                commission.current() + "/" + commission.target()),
                        Lang.line("menu.progress.commission.reward", NamedTextColor.GOLD,
                                "reward", commission.reward()),
                        Lang.line(actionKey, commission.claimed()
                                ? NamedTextColor.DARK_GREEN : NamedTextColor.YELLOW)))
                .build();

        if (commission.claimed()) {
            set(slot, card);
        } else if (done) {
            set(slot, card, event -> {
                message(design.claimCommission(viewer.getUniqueId(), commission.id()));
                redraw();
            });
        } else {
            // Rerolling discards today's progress on this commission.
            setConfirm(slot, card, () -> {
                message(design.rerollCommission(viewer.getUniqueId(), commission.id()));
                redraw();
            });
        }
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
