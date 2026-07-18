package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.storage.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public final class MainHubMenu extends Menu {

    private final GuiManager gui;

    public MainHubMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text("IdleFarm", NamedTextColor.DARK_GREEN);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }

        PlayerData data = gui.dataStore().getOnline(viewer.getUniqueId());
        String currency = gui.plugin().getConfig().getString("currency-name", "Coins");
        long produced = gui.nodeStore().getByOwner(viewer.getUniqueId()).stream()
                .filter(n -> n.getType().isProduction()).count();

        set(10, Icon.of(Material.GRASS_BLOCK).name("Nodes", NamedTextColor.GREEN)
                .lore(List.of(produced + " production nodes", "Click to manage"), NamedTextColor.GRAY).build(),
                e -> gui.openNodes(viewer));

        set(12, Icon.of(Material.CHEST).name("Warehouse", NamedTextColor.GOLD)
                .lore(List.of(gui.warehouseService().total(viewer.getUniqueId()) + "/"
                        + gui.warehouseService().getCapacity(viewer.getUniqueId()) + " stored"),
                        NamedTextColor.GRAY).build(),
                e -> gui.openWarehouse(viewer, viewer.getUniqueId()));

        set(14, Icon.of(Material.VILLAGER_SPAWN_EGG).name("Workers", NamedTextColor.AQUA)
                .lore(List.of("Hire & fuse workers"), NamedTextColor.GRAY).build(),
                e -> gui.openWorkers(viewer));

        double balance = data == null ? 0 : data.getBalance();
        set(16, Icon.of(Material.SUNFLOWER).name("Profile", NamedTextColor.YELLOW)
                .lore(List.of(formatAmount(balance) + " " + currency,
                        (data == null ? 0 : data.getTotalOnlineMinutes()) + " min online"),
                        NamedTextColor.GRAY).build(), null);

        set(22, Icon.of(Material.BARRIER).name("Close", NamedTextColor.RED).build(),
                e -> viewer.closeInventory());
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }
}
