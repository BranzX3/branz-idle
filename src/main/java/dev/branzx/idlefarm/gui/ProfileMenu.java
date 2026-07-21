package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Clickable account summary and personal settings surface. */
public final class ProfileMenu extends Menu {

    private final GuiManager gui;

    public ProfileMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.profile.title"), NamedTextColor.YELLOW);
    }

    @Override
    protected void build() {
        PlayerData data = gui.dataStore().getOnline(viewer.getUniqueId());
        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        double balance = data == null ? 0 : data.getBalance();
        long minutes = data == null ? 0 : data.getTotalOnlineMinutes();
        int streak = gui.streakService() == null ? 0
                : gui.streakService().currentStreak(viewer.getUniqueId());

        set(SUMMARY_SLOT, Icon.head(gui.skinHeadCache(), viewer.getName())
                .name(viewer.getName(), NamedTextColor.YELLOW)
                .loreComponents(List.of(
                        Ui.line(Ui.num(balance) + " " + currency, NamedTextColor.GOLD),
                        Ui.line(Ui.time(minutes * 60_000L) + " online", NamedTextColor.AQUA),
                        Ui.line("Login streak: " + streak + " day(s)", NamedTextColor.RED)))
                .build());

        int usedNodes = (int) gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(node -> node.getType().isProduction()).count();
        set(11, Icon.of(Material.GRASS_BLOCK)
                .name("Node Capacity", NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Ui.line(usedNodes + "/"
                                + gui.claimService().nodeCap(viewer.getUniqueId())
                                + " production Nodes", NamedTextColor.GRAY),
                        Ui.click("open Node Control")))
                .build(), event -> gui.openNodes(viewer));

        var warehouse = gui.warehouseService();
        int vault = warehouse.vaultTotal(viewer.getUniqueId());
        int vaultCapacity = warehouse.vaultCapacity(viewer.getUniqueId());
        int silo = warehouse.siloTotal(viewer.getUniqueId());
        int siloCapacity = warehouse.siloCapacity(viewer.getUniqueId());
        set(13, Icon.of(Material.CHEST)
                .name("Storage", NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.bar("Vault", vaultCapacity == 0 ? 0 : vault / (double) vaultCapacity,
                                NamedTextColor.GOLD, vault + "/" + vaultCapacity),
                        Ui.bar("Silo", siloCapacity == 0 ? 0 : silo / (double) siloCapacity,
                                NamedTextColor.AQUA, silo + "/" + siloCapacity),
                        Ui.click("open Warehouse")))
                .build(), event -> gui.openWarehouse(viewer, viewer.getUniqueId()));

        long moneyBoost = gui.boosterService() == null ? 0
                : gui.boosterService().remainingMillis(viewer.getUniqueId(), "money");
        long productionBoost = gui.boosterService() == null ? 0
                : gui.boosterService().remainingMillis(viewer.getUniqueId(), "production");
        set(15, Icon.of(Material.BEACON)
                .name("Active Effects", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Ui.line("Money: " + (moneyBoost > 0
                                ? Ui.time(moneyBoost) : "inactive"), NamedTextColor.GRAY),
                        Ui.line("Production: " + (productionBoost > 0
                                ? Ui.time(productionBoost) : "inactive"), NamedTextColor.GRAY),
                        Ui.click("open Upgrades")))
                .build(), event -> gui.openShop(viewer));

        set(22, Icon.of(Material.PAPER)
                .name("Command Shortcuts", NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.line("/idle opens this interface", NamedTextColor.GRAY),
                        Ui.line("/idle visit <player>", NamedTextColor.GRAY),
                        Ui.line("/idle trade <player>", NamedTextColor.GRAY),
                        Ui.line("/idle balance shows a chat summary", NamedTextColor.GRAY)))
                .build());
        navBarToHub(gui);
    }

    @Override
    protected Material frameMaterial() {
        return Material.YELLOW_STAINED_GLASS_PANE;
    }
}
