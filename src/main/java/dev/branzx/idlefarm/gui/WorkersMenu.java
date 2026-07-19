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

        set(15, Icon.of(Material.SMITHING_TABLE).name("Fuse Workers", NamedTextColor.LIGHT_PURPLE)
                .lore(List.of("Hold a contract, then click:",
                        "combines 2 same-rarity workers",
                        "(any level) — rolls to upgrade.",
                        "Fail consumes both but builds pity."),
                        NamedTextColor.GRAY).build(), e -> fuse());

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

    private void fuse() {
        var held = gui.workerService().fromItem(viewer.getInventory().getItemInMainHand());
        if (held == null) {
            viewer.sendMessage(Component.text("Hold a worker contract to pick the fuse rarity.",
                    NamedTextColor.RED));
            return;
        }
        List<dev.branzx.idlefarm.worker.WorkerRecord> materials = new java.util.ArrayList<>();
        List<Integer> slots = new java.util.ArrayList<>();
        ItemStack[] contents = viewer.getInventory().getContents();
        for (int slot = 0; slot < contents.length && materials.size() < 2; slot++) {
            var record = gui.workerService().fromItem(contents[slot]);
            if (record != null && record.getRarity() == held.getRarity()
                    && dev.branzx.idlefarm.worker.WorkerRecord.STATE_ITEM.equals(record.getState())) {
                materials.add(record);
                slots.add(slot);
            }
        }
        if (materials.size() < 2) {
            viewer.sendMessage(Component.text("Need 2 " + held.getRarity()
                    + " workers (found " + materials.size() + ").", NamedTextColor.RED));
            return;
        }
        double chance = gui.workerService().fuseChance(viewer.getUniqueId(), held.getRarity());
        int pity = gui.workerService().pityCount(viewer.getUniqueId(), held.getRarity());
        new ConfirmMenu(viewer, "Fuse 2x " + held.getRarity() + "?",
                List.of("Success: " + Math.round(chance * 100) + "% → " + held.getRarity().next(),
                        "Pity stacks: " + pity,
                        "Fail consumes both workers",
                        "and raises your next chance."),
                () -> doFuse(materials, slots),
                () -> new WorkersMenu(viewer, gui).open()).open();
    }

    private void doFuse(List<dev.branzx.idlefarm.worker.WorkerRecord> materials, List<Integer> slots) {
        WorkerService.Result result = gui.workerService().fuse(viewer.getUniqueId(), materials);
        for (int slot : slots) {
            viewer.getInventory().setItem(slot, null);
        }
        if (result.success() && result.item() != null) {
            giveOrDrop(result.item());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        new WorkersMenu(viewer, gui).open();
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
