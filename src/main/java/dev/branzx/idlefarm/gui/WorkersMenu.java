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
        return Component.text("IdleFarm | Crew", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        fill();

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
        set(10, Icon.of(Material.EXPERIENCE_BOTTLE).name("Hire Worker", NamedTextColor.GREEN)
                .lore(odds, NamedTextColor.GRAY).build(), e ->
                        new ConfirmMenu(viewer, "Hire a Worker?",
                                List.of("Cost: " + formatAmount(hireCost),
                                        "Rarity, stats and Trait are random",
                                        "The result is resolved server-side"),
                                this::hire,
                                () -> new WorkersMenu(viewer, gui).open()).open());

        int used = gui.workerStore().bagCount(viewer.getUniqueId());
        int cap = gui.workerService().bagCapacity(viewer.getUniqueId());
        set(12, Icon.of(Material.CHEST_MINECART).name("Worker Bag", NamedTextColor.AQUA)
                .lore(List.of(used + "/" + cap + " stored",
                        "View, withdraw & deposit workers"), NamedTextColor.GRAY).build(),
                e -> gui.openWorkerBag(viewer));

        set(14, Icon.of(Material.SMITHING_TABLE).name("Fuse Station", NamedTextColor.LIGHT_PURPLE)
                .lore(List.of("Pick 2 same-rarity workers",
                        "and see live odds before you roll."),
                        NamedTextColor.GRAY).build(), e -> gui.openFuse(viewer));

        backToHub(gui);
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
