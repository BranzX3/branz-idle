package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's virtual worker bag: a paged, click-driven roster. Left-click a
 * worker to withdraw it as a tradeable item; a "Deposit inventory" button
 * pulls loose contracts back in. Capacity is expandable with Money.
 */
public final class WorkerBagMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final GuiManager gui;
    private final int page;

    public WorkerBagMenu(Player viewer, GuiManager gui, int page) {
        super(viewer);
        this.gui = gui;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        int used = gui.workerStore().bagCount(viewer.getUniqueId());
        int cap = gui.workerService().bagCapacity(viewer.getUniqueId());
        return Component.text("Worker Bag  " + used + "/" + cap, NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        List<WorkerRecord> bag = new ArrayList<>(gui.workerStore().getBag(viewer.getUniqueId()));
        bag.sort((x, y) -> {
            int r = y.getRarity().ordinal() - x.getRarity().ordinal();
            return r != 0 ? r : y.getLevel() - x.getLevel();
        });
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < bag.size(); i++) {
            WorkerRecord worker = bag.get(start + i);
            List<Component> lore = new ArrayList<>(gui.workerService().workerLore(worker));
            lore.add(Ui.line("Click: withdraw as item (to trade)", NamedTextColor.DARK_GRAY));
            set(i, Icon.head(worker.getSkin()).name("✦ " + worker.getName(), worker.getRarity().color())
                    .loreComponents(lore).build(), e -> withdraw(worker.getWorkerUuid()));
        }
        if (bag.isEmpty()) {
            set(22, Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                    .name("Bag is empty", NamedTextColor.GRAY)
                    .lore("Hire workers to fill it up", NamedTextColor.DARK_GRAY).build());
        }

        for (int i = 45; i < 54; i++) {
            set(i, Icon.filler());
        }
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("Previous", NamedTextColor.YELLOW).build(),
                    e -> new WorkerBagMenu(viewer, gui, page - 1).open());
        }
        set(47, Icon.of(Material.HOPPER).name("Deposit from Inventory", NamedTextColor.GREEN)
                .lore("Pull loose worker contracts into the bag", NamedTextColor.GRAY).build(),
                e -> depositAll());
        set(49, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
        double expandCost = gui.plugin().getConfig().getDouble("workers.bag.expand-cost", 5000);
        int step = gui.plugin().getConfig().getInt("workers.bag.expand-step", 9);
        set(51, Icon.of(Material.ENDER_CHEST).name("Expand +" + step, NamedTextColor.AQUA)
                .lore("Cost: " + Ui.num(expandCost), NamedTextColor.GRAY).build(), e -> expand());
        if (start + PAGE_SIZE < bag.size()) {
            set(53, Icon.of(Material.ARROW).name("Next", NamedTextColor.YELLOW).build(),
                    e -> new WorkerBagMenu(viewer, gui, page + 1).open());
        }
    }

    private void withdraw(java.util.UUID workerUuid) {
        WorkerRecord worker = gui.workerStore().get(workerUuid);
        if (worker == null || !worker.isInBag()) {
            redraw();
            return;
        }
        ItemStack item = gui.workerService().withdraw(viewer.getUniqueId(), worker);
        if (item != null) {
            var leftover = viewer.getInventory().addItem(item);
            for (ItemStack overflow : leftover.values()) {
                viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
            }
        }
        redraw();
    }

    private void depositAll() {
        int deposited = 0;
        ItemStack[] contents = viewer.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            WorkerRecord record = gui.workerService().fromItem(contents[i]);
            if (record == null || !WorkerRecord.STATE_ITEM.equals(record.getState())) {
                continue;
            }
            String error = gui.workerService().depositItem(viewer.getUniqueId(), record);
            if (error != null) {
                viewer.sendMessage(Component.text(error, NamedTextColor.RED));
                break; // bag full
            }
            // Remove the single item token that we just absorbed.
            ItemStack item = contents[i];
            if (item.getAmount() <= 1) {
                viewer.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - 1);
            }
            deposited++;
        }
        if (deposited > 0) {
            viewer.sendMessage(Component.text("Deposited " + deposited + " worker(s) to the bag.",
                    NamedTextColor.GREEN));
        }
        redraw();
    }

    private void expand() {
        String error = gui.workerService().expandBag(viewer.getUniqueId());
        viewer.sendMessage(Component.text(error == null ? "Worker bag expanded!" : error,
                error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
    }
}
