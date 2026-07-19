package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive fuse station: the player drops two worker contracts into the
 * two input slots. The center panel live-computes the success chance, pity,
 * and result rarity; the Fuse button rolls once both slots hold valid,
 * same-rarity workers. Any unused items are returned on close.
 */
public final class FuseMenu extends Menu {

    private static final int LEFT = 11;
    private static final int RIGHT = 15;
    private static final int INFO = 13;
    private static final int FUSE_BTN = 31;

    private final GuiManager gui;

    public FuseMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 5;
    }

    @Override
    protected Component title() {
        return Component.text("Fuse Workers", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        // Frame everything except the two input slots.
        for (int i = 0; i < rows() * 9; i++) {
            if (i != LEFT && i != RIGHT) {
                set(i, Icon.filler());
            }
        }
        markInputSlot(LEFT);
        markInputSlot(RIGHT);
        set(LEFT, placeholder("Worker A"));
        set(RIGHT, placeholder("Worker B"));
        refreshInfo();
        set(40, Icon.of(Material.BARRIER).name("Close", NamedTextColor.RED)
                .lore("Unused workers are returned", NamedTextColor.GRAY).build(),
                e -> viewer.closeInventory());
    }

    private ItemStack placeholder(String label) {
        return Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                .name(label, NamedTextColor.GRAY)
                .lore("Drop a worker contract here", NamedTextColor.DARK_GRAY).build();
    }

    private boolean isPlaceholder(ItemStack item) {
        return item == null || item.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE
                || item.getType() == Material.AIR;
    }

    @Override
    public void onInputChanged() {
        // Recompute after the click/drag settles this tick.
        Bukkit.getScheduler().runTask(gui.plugin(), this::refreshInfo);
    }

    private void refreshInfo() {
        WorkerRecord a = gui.workerService().fromItem(itemAt(LEFT));
        WorkerRecord b = gui.workerService().fromItem(itemAt(RIGHT));

        // Restore placeholders if a slot was emptied.
        if (isPlaceholder(itemAt(LEFT))) {
            setRaw(LEFT, placeholder("Worker A"));
        }
        if (isPlaceholder(itemAt(RIGHT))) {
            setRaw(RIGHT, placeholder("Worker B"));
        }

        List<Component> lore = new ArrayList<>();
        String error = validate(a, b);
        if (error != null) {
            lore.add(Ui.line(error, NamedTextColor.GRAY));
            set(INFO, Icon.of(Material.GRAY_DYE).name("Place two workers", NamedTextColor.GRAY)
                    .loreComponents(lore).build());
            set(FUSE_BTN, Icon.of(Material.RED_STAINED_GLASS_PANE)
                    .name("Fuse (not ready)", NamedTextColor.RED).build());
            return;
        }

        Rarity rarity = a.getRarity();
        Rarity next = rarity.next();
        double chance = gui.workerService().fuseChance(viewer.getUniqueId(), rarity);
        int pity = gui.workerService().pityCount(viewer.getUniqueId(), rarity);

        lore.add(Ui.stars(rarity).append(Ui.line("  " + rarity + " → " + next, next.color())));
        lore.add(Ui.divider());
        lore.add(Ui.bar("Success", chance, NamedTextColor.GREEN, Math.round(chance * 100) + "%"));
        lore.add(Ui.line("Pity stacks: " + pity, NamedTextColor.AQUA));
        lore.add(Ui.line("Each fail: +" + Math.round(gui.plugin().getConfig()
                .getDouble("workers.fuse.pity-per-fail", 0.1) * 100) + "% next time", NamedTextColor.GRAY));
        lore.add(Ui.divider());
        lore.add(Ui.line("A: " + a.getName() + " (" + a.getRarity() + ")", NamedTextColor.WHITE));
        lore.add(Ui.line("B: " + b.getName() + " (" + b.getRarity() + ")", NamedTextColor.WHITE));
        lore.add(Ui.divider());
        lore.add(Ui.line("Fail consumes BOTH workers", NamedTextColor.RED));
        set(INFO, Icon.of(Material.SMITHING_TABLE).name("Fuse Preview", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(lore).build());
        set(FUSE_BTN, Icon.of(Material.LIME_STAINED_GLASS_PANE)
                .name("★ FUSE — " + Math.round(chance * 100) + "% ★", NamedTextColor.GREEN)
                .lore("Click to roll!", NamedTextColor.GRAY).build(),
                e -> doFuse(a, b));
    }

    /** Null when both slots hold valid same-rarity item-form workers. */
    private String validate(WorkerRecord a, WorkerRecord b) {
        if (a == null || b == null) {
            return "Drop two worker contracts in the slots.";
        }
        if (a.getWorkerUuid().equals(b.getWorkerUuid())) {
            return "Two different workers are required.";
        }
        if (a.getRarity() != b.getRarity()) {
            return "Both workers must share the same rarity.";
        }
        if (a.getRarity().next() == null) {
            return "Legendary workers cannot be fused.";
        }
        if (!WorkerRecord.STATE_ITEM.equals(a.getState())
                || !WorkerRecord.STATE_ITEM.equals(b.getState())) {
            return "A worker is still assigned — eject it first.";
        }
        return null;
    }

    private void doFuse(WorkerRecord a, WorkerRecord b) {
        WorkerService.Result result = gui.workerService().fuse(viewer.getUniqueId(), List.of(a, b));
        // Both inputs are consumed by the service regardless of outcome.
        setRaw(LEFT, null);
        setRaw(RIGHT, null);
        if (result.success() && result.item() != null) {
            giveOrDrop(result.item());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        refreshInfo();
    }

    @Override
    public void onClose() {
        // Return whatever real worker items are still sitting in the slots.
        for (int slot : new int[] {LEFT, RIGHT}) {
            ItemStack item = itemAt(slot);
            if (!isPlaceholder(item) && gui.workerService().fromItem(item) != null) {
                giveOrDrop(item);
            }
            setRaw(slot, null);
        }
    }

    private void giveOrDrop(ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        for (ItemStack overflow : leftover.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
        }
    }
}
