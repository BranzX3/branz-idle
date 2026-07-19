package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.BoosterService;
import dev.branzx.idlefarm.service.PerkService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Boosters (timed multipliers) and one-time convenience perks. */
public final class ShopMenu extends Menu {

    private final GuiManager gui;

    public ShopMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text("Boosters & Perks", NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }
        BoosterService boosters = gui.boosterService();
        PerkService perks = gui.perkService();

        // Boosters row.
        booster(10, BoosterService.MONEY, Material.GOLD_INGOT, "Money Booster");
        booster(12, BoosterService.PRODUCTION, Material.REDSTONE, "Production Booster");

        // Perks row.
        perk(14, PerkService.AUTO_COLLECT, Material.HOPPER, "Auto-Collect",
                List.of("Node buffers flush to your", "Warehouse automatically", "(endgame QoL)"));
        perk(16, PerkService.REMOTE_COLLECT, Material.ENDER_EYE, "Remote Collect",
                List.of("Collect All button in the hub,", "works from anywhere"));

        set(31, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
    }

    private void booster(int slot, String type, Material material, String label) {
        BoosterService boosters = gui.boosterService();
        long remaining = boosters.remainingMillis(viewer.getUniqueId(), type);
        var status = remaining > 0
                ? Ui.line("● ACTIVE — " + Ui.time(remaining) + " left (buy extends)", NamedTextColor.GREEN)
                : Ui.line("○ Inactive", NamedTextColor.DARK_GRAY);
        set(slot, Icon.of(material).name(label, NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line("×" + boosters.boostMultiplier(type) + " for "
                                + boosters.durationMinutes(type) + "m", NamedTextColor.AQUA),
                        Ui.line("⛁ " + Ui.num(boosters.cost(type)), NamedTextColor.GOLD),
                        Ui.divider(),
                        status)).build(),
                e -> {
                    String error = boosters.buy(viewer.getUniqueId(), type);
                    viewer.sendMessage(Component.text(error == null ? label + " activated!" : error,
                            error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
                    redraw();
                });
    }

    private void perk(int slot, String perk, Material material, String label, List<String> description) {
        PerkService perks = gui.perkService();
        boolean owned = perks.has(viewer.getUniqueId(), perk);
        List<String> lore = new java.util.ArrayList<>(description);
        lore.add(owned ? "OWNED" : "Cost: " + perks.cost(perk));
        set(slot, Icon.of(material).name(label, owned ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .lore(lore, NamedTextColor.GRAY).build(),
                e -> {
                    if (owned) {
                        viewer.sendMessage(Component.text("You already own " + label + ".",
                                NamedTextColor.YELLOW));
                        return;
                    }
                    String error = perks.buy(viewer.getUniqueId(), perk);
                    viewer.sendMessage(Component.text(error == null ? label + " unlocked!" : error,
                            error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
                    redraw();
                });
    }
}
