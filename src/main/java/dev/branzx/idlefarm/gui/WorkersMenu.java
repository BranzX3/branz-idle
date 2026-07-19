package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.WorkerService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class WorkersMenu extends Menu {

    private final GuiManager gui;

    public WorkersMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text("Workers", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }

        double hireCost = gui.workerService().hireCost();
        List<String> odds = new java.util.ArrayList<>();
        odds.add("Cost: " + formatAmount(hireCost));
        odds.add("Odds:");
        var oddsSection = gui.plugin().getConfig().getConfigurationSection("workers.gacha-odds");
        if (oddsSection != null) {
            for (String key : oddsSection.getKeys(false)) {
                odds.add("  " + key + ": " + oddsSection.getDouble(key) + "%");
            }
        }
        set(11, Icon.of(Material.EXPERIENCE_BOTTLE).name("Hire Worker", NamedTextColor.GREEN)
                .lore(odds, NamedTextColor.GRAY).build(), e -> hire());

        set(15, Icon.of(Material.SMITHING_TABLE).name("Fuse Station", NamedTextColor.LIGHT_PURPLE)
                .lore(List.of("Open the fuse station:",
                        "drop 2 same-rarity workers in",
                        "the slots and see live odds",
                        "before you roll."),
                        NamedTextColor.GRAY).build(), e -> gui.openFuse(viewer));

        set(22, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
    }

    private void hire() {
        WorkerService.Result result = gui.workerService().hire(viewer.getUniqueId());
        if (result.success() && result.item() != null) {
            giveOrDrop(result.item());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
    }

    private void giveOrDrop(ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
        }
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }
}
