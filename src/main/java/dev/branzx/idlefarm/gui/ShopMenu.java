package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.BoosterService;
import dev.branzx.idlefarm.service.PerkService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Coin-funded convenience purchases with explicit review before spending. */
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
        return Component.text("IdleFarm | Upgrades", NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        fill();
        set(4, Icon.of(Material.EMERALD)
                .name("Boosters & Convenience", NamedTextColor.GOLD)
                .lore("Every purchase is reviewed before Coins are spent",
                        NamedTextColor.GRAY).build());

        booster(11, BoosterService.MONEY, Material.GOLD_INGOT, "Money Booster");
        booster(13, BoosterService.PRODUCTION, Material.REDSTONE, "Production Booster");
        perk(20, PerkService.AUTO_COLLECT, Material.HOPPER, "Auto-Collect",
                List.of("Periodically sends Node buffers", "to your Warehouse"));
        perk(22, PerkService.REMOTE_COLLECT, Material.ENDER_EYE, "Remote Collect",
                List.of("Adds Quick Collect All", "to the Home screen"));

        backToHub(gui);
    }

    private void booster(int slot, String type, Material material, String label) {
        BoosterService boosters = gui.boosterService();
        long remaining = boosters.remainingMillis(viewer.getUniqueId(), type);
        double cost = boosters.cost(type);
        List<Component> lore = new ArrayList<>();
        lore.add(Ui.line("x" + boosters.boostMultiplier(type) + " for "
                + boosters.durationMinutes(type) + "m", NamedTextColor.AQUA));
        lore.add(Ui.line("Cost: " + Ui.num(cost), NamedTextColor.GOLD));
        lore.add(remaining > 0
                ? Ui.status("ACTIVE | " + Ui.time(remaining) + " LEFT", NamedTextColor.GREEN)
                : Ui.status("INACTIVE", NamedTextColor.DARK_GRAY));
        lore.add(Ui.click("review purchase"));
        set(slot, Icon.of(material).name(label, NamedTextColor.YELLOW)
                .loreComponents(lore).build(), event ->
                new ConfirmMenu(viewer, "Buy " + label + "?",
                        List.of("Cost: " + Ui.num(cost),
                                "Duration: " + boosters.durationMinutes(type) + " minutes",
                                remaining > 0
                                        ? "Adds time to the active booster"
                                        : "Activates immediately"),
                        () -> buyBooster(type, label),
                        () -> new ShopMenu(viewer, gui).open()).open());
    }

    private void buyBooster(String type, String label) {
        String error = gui.boosterService().buy(viewer.getUniqueId(), type);
        viewer.sendMessage(Component.text(error == null ? label + " activated." : error,
                error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        new ShopMenu(viewer, gui).open();
    }

    private void perk(int slot, String perk, Material material, String label,
                      List<String> description) {
        PerkService perks = gui.perkService();
        boolean owned = perks.has(viewer.getUniqueId(), perk);
        double cost = perks.cost(perk);
        List<Component> lore = new ArrayList<>();
        description.forEach(line -> lore.add(Ui.line(line, NamedTextColor.GRAY)));
        lore.add(owned ? Ui.status("OWNED", NamedTextColor.GREEN)
                : Ui.line("Cost: " + Ui.num(cost), NamedTextColor.GOLD));
        if (!owned) {
            lore.add(Ui.click("review purchase"));
        }
        set(slot, Icon.of(material)
                .name(label, owned ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .loreComponents(lore).build(), owned ? null : event ->
                        new ConfirmMenu(viewer, "Unlock " + label + "?",
                                List.of("Cost: " + Ui.num(cost),
                                        "Permanent convenience unlock"),
                                () -> buyPerk(perk, label),
                                () -> new ShopMenu(viewer, gui).open()).open());
    }

    private void buyPerk(String perk, String label) {
        String error = gui.perkService().buy(viewer.getUniqueId(), perk);
        viewer.sendMessage(Component.text(error == null ? label + " unlocked." : error,
                error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        new ShopMenu(viewer, gui).open();
    }
}
