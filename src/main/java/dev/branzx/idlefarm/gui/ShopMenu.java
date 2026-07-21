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
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.shop.title"), NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        set(SUMMARY_SLOT, Icon.of(Material.EMERALD)
                .name(Lang.get("menu.shop.summary"), NamedTextColor.GOLD)
                .lore(Lang.get("menu.shop.hint"), NamedTextColor.GRAY).build());

        // One grid, boosters then perks: adding a new purchasable means
        // appending here, not finding free slots.
        booster(CONTENT_GRID[0], BoosterService.MONEY, Material.GOLD_INGOT, "money");
        booster(CONTENT_GRID[1], BoosterService.PRODUCTION, Material.REDSTONE, "production");
        perk(CONTENT_GRID[2], PerkService.AUTO_COLLECT, Material.HOPPER, "auto-collect");
        perk(CONTENT_GRID[3], PerkService.REMOTE_COLLECT, Material.ENDER_EYE, "remote-collect");

        navBarToHub(gui);
    }

    private void booster(int slot, String type, Material material, String key) {
        BoosterService boosters = gui.boosterService();
        long remaining = boosters.remainingMillis(viewer.getUniqueId(), type);
        double cost = boosters.cost(type);
        List<Component> lore = new ArrayList<>();
        lore.add(Lang.line("menu.shop.booster.effect", NamedTextColor.AQUA,
                "multiplier", boosters.boostMultiplier(type),
                "minutes", boosters.durationMinutes(type)));
        lore.add(Lang.line("menu.shop.cost", NamedTextColor.GOLD, "cost", Ui.num(cost)));
        lore.add(remaining > 0
                ? Ui.status(Lang.get("menu.shop.booster.active",
                        "time", Ui.time(remaining)), NamedTextColor.GREEN)
                : Ui.status(Lang.get("menu.shop.booster.inactive"), NamedTextColor.DARK_GRAY));
        lore.add(Lang.line(remaining > 0 ? "menu.shop.booster.extends"
                : "menu.shop.booster.starts", NamedTextColor.GRAY));
        lore.add(Lang.click("menu.shop.buy"));
        setConfirm(slot, Icon.of(material)
                .name(Lang.get("menu.shop.booster." + key), NamedTextColor.YELLOW)
                .loreComponents(lore).build(), () -> {
                    String error = gui.boosterService().buy(viewer.getUniqueId(), type);
                    viewer.sendMessage(Component.text(error == null
                                    ? Lang.get("menu.shop.booster.bought",
                                            "name", Lang.get("menu.shop.booster." + key))
                                    : error,
                            error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
                    redraw();
                });
    }

    private void perk(int slot, String perk, Material material, String key) {
        PerkService perks = gui.perkService();
        boolean owned = perks.has(viewer.getUniqueId(), perk);
        double cost = perks.cost(perk);
        List<Component> lore = new ArrayList<>();
        lore.add(Lang.line("menu.shop.perk." + key + ".effect", NamedTextColor.GRAY));
        lore.add(owned ? Ui.status(Lang.get("menu.shop.perk.owned"), NamedTextColor.GREEN)
                : Lang.line("menu.shop.cost", NamedTextColor.GOLD, "cost", Ui.num(cost)));
        var icon = Icon.of(material)
                .name(Lang.get("menu.shop.perk." + key + ".name"),
                        owned ? NamedTextColor.GREEN : NamedTextColor.AQUA);
        if (owned) {
            set(slot, icon.loreComponents(lore).build());
            return;
        }
        lore.add(Lang.line("menu.shop.perk.permanent", NamedTextColor.DARK_GRAY));
        lore.add(Lang.click("menu.shop.buy"));
        setConfirm(slot, icon.loreComponents(lore).build(), () -> {
            String error = gui.perkService().buy(viewer.getUniqueId(), perk);
            viewer.sendMessage(Component.text(error == null
                            ? Lang.get("menu.shop.perk.bought",
                                    "name", Lang.get("menu.shop.perk." + key + ".name"))
                            : error,
                    error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
            redraw();
        });
    }

    @Override
    protected Material frameMaterial() {
        return Material.YELLOW_STAINED_GLASS_PANE;
    }
}
