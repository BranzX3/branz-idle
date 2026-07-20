package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.service.WorkerService;
import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Click-driven fuse station (no item movement, so no placeholder/loss bugs).
 * Two "select" slots open a {@link WorkerPickerMenu}; picked workers stay as
 * items in the inventory and are only consumed when the Fuse button rolls.
 * Slot B is filtered to slot A's rarity.
 */
public final class FuseMenu extends Menu {

    private static final int LEFT = 11;
    private static final int RIGHT = 15;
    private static final int INFO = 13;
    private static final int FUSE_BTN = 31;

    private final GuiManager gui;
    private UUID pickedA;
    private UUID pickedB;

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
        return Component.text("Fuse Station", NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        for (int i = 0; i < rows() * 9; i++) {
            set(i, Icon.filler());
        }
        WorkerRecord a = resolve(pickedA);
        WorkerRecord b = resolve(pickedB);
        // Drop stale picks (item left inventory / got assigned elsewhere).
        if (pickedA != null && a == null) {
            pickedA = null;
        }
        if (pickedB != null && b == null) {
            pickedB = null;
        }

        drawSlot(LEFT, a, "Worker A", null, chosen -> {
            pickedA = chosen.workerUuid();
            // Changing A may invalidate B's rarity match.
            if (pickedB != null) {
                WorkerRecord bb = resolve(pickedB);
                if (bb == null || bb.getRarity() != chosen.record().getRarity()) {
                    pickedB = null;
                }
            }
        });
        Rarity filterB = a == null ? null : a.getRarity();
        drawSlot(RIGHT, b, "Worker B", filterB, chosen -> pickedB = chosen.workerUuid());

        refreshInfo(a, b);

        set(40, Icon.of(Material.BARRIER).name("Close", NamedTextColor.RED).build(),
                e -> viewer.closeInventory());
    }

    private void drawSlot(int slot, WorkerRecord picked, String label, Rarity filter,
                          java.util.function.Consumer<WorkerPickerMenu.Choice> onPick) {
        if (picked != null) {
            List<Component> lore = new ArrayList<>(gui.workerService().workerLore(picked));
            lore.add(Ui.line("Click to clear", NamedTextColor.DARK_GRAY));
            set(slot, Icon.head(gui.skinHeadCache(), picked.getSkin())
                    .name("✦ " + picked.getName(), picked.getRarity().color())
                    .loreComponents(lore).build(), e -> {
                if (slot == LEFT) {
                    pickedA = null;
                    pickedB = null; // clearing A drops B's rarity lock
                } else {
                    pickedB = null;
                }
                redraw();
            });
        } else {
            set(slot, Icon.of(Material.ITEM_FRAME).name("Select " + label, NamedTextColor.AQUA)
                    .lore(filter == null ? "Click to choose a worker"
                            : "Click to choose a " + filter + " worker", NamedTextColor.GRAY).build(),
                    e -> new WorkerPickerMenu(viewer, gui, filter,
                            slot == RIGHT ? pickedA : null,
                            "Choose " + label,
                            chosen -> {
                                onPick.accept(chosen);
                                open(); // back to the fuse station with the pick applied
                            },
                            this::open).open());
        }
    }

    private void refreshInfo(WorkerRecord a, WorkerRecord b) {
        List<Component> lore = new ArrayList<>();
        String error = validate(a, b);
        if (error != null) {
            lore.add(Ui.line(error, NamedTextColor.GRAY));
            set(INFO, Icon.of(Material.GRAY_DYE).name("Select two workers", NamedTextColor.GRAY)
                    .loreComponents(lore).build());
            set(FUSE_BTN, Icon.of(Material.RED_STAINED_GLASS_PANE)
                    .name("Fuse (not ready)", NamedTextColor.RED).build());
            return;
        }
        Rarity rarity = a.getRarity();
        Rarity next = rarity.next();
        double chance = gui.workerService().fuseChance(viewer.getUniqueId(), rarity);
        int pity = gui.workerService().pityCount(viewer.getUniqueId(), rarity);

        lore.add(Ui.stars(rarity).append(Ui.line("  " + rarity + " -> " + next, next.color())));
        lore.add(Ui.divider());
        lore.add(Ui.bar("Success", chance, NamedTextColor.GREEN, Math.round(chance * 100) + "%"));
        lore.add(Ui.line("Pity stacks: " + pity, NamedTextColor.AQUA));
        lore.add(Ui.line("Each fail: +" + Math.round(gui.plugin().getConfig()
                .getDouble("workers.fuse.pity-per-fail", 0.1) * 100) + "% next time", NamedTextColor.GRAY));
        lore.add(Ui.divider());
        lore.add(Ui.line("Fail: protected base survives; duplicate is consumed", NamedTextColor.YELLOW));
        set(INFO, Icon.of(Material.SMITHING_TABLE).name("Fuse Preview", NamedTextColor.LIGHT_PURPLE)
                .loreComponents(lore).build());
        set(FUSE_BTN, Icon.of(Material.LIME_STAINED_GLASS_PANE)
                .name("* FUSE — " + Math.round(chance * 100) + "% *", NamedTextColor.GREEN)
                .lore("Click to roll!", NamedTextColor.GRAY).build(),
                e -> doFuse(a, b));
    }

    private String validate(WorkerRecord a, WorkerRecord b) {
        if (a == null || b == null) {
            return "Select two worker contracts.";
        }
        if (a.getRarity() != b.getRarity()) {
            return "Both workers must share the same rarity.";
        }
        if (a.getRarity().next() == null) {
            return "Legendary workers cannot be fused.";
        }
        return null;
    }

    private void doFuse(WorkerRecord a, WorkerRecord b) {
        // Loose inventory items must be pulled out (fuse deletes the DB rows,
        // which would otherwise leave dead item tokens behind); bag workers
        // are removed by the service via delete().
        boolean aItem = WorkerRecord.STATE_ITEM.equals(a.getState());
        boolean bItem = WorkerRecord.STATE_ITEM.equals(b.getState());
        WorkerService.Result result = gui.workerService().fuse(viewer.getUniqueId(), List.of(a, b));
        if (aItem && gui.workerStore().get(a.getWorkerUuid()) == null) {
            WorkerPickerMenu.consumeFromInventory(viewer, gui, a.getWorkerUuid());
        }
        if (bItem && gui.workerStore().get(b.getWorkerUuid()) == null) {
            WorkerPickerMenu.consumeFromInventory(viewer, gui, b.getWorkerUuid());
        }
        if (result.success() && result.item() != null) {
            giveOrDrop(result.item());
        }
        viewer.sendMessage(Component.text(result.message(),
                result.success() ? NamedTextColor.GREEN : NamedTextColor.RED));
        pickedA = null;
        pickedB = null;
        redraw();
    }

    private WorkerRecord resolve(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        WorkerRecord record = gui.workerStore().get(uuid);
        if (record == null) {
            return null;
        }
        // Available = in the player's bag, or a loose item still in inventory.
        if (WorkerRecord.STATE_BAG.equals(record.getState())
                && viewer.getUniqueId().equals(record.getOwnerUuid())) {
            return record;
        }
        if (WorkerRecord.STATE_ITEM.equals(record.getState()) && hasInInventory(uuid)) {
            return record;
        }
        return null;
    }

    private boolean hasInInventory(UUID uuid) {
        for (var item : viewer.getInventory().getContents()) {
            WorkerRecord record = gui.workerService().fromItem(item);
            if (record != null && record.getWorkerUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private void giveOrDrop(org.bukkit.inventory.ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        for (var overflow : leftover.values()) {
            viewer.getWorld().dropItemNaturally(viewer.getLocation(), overflow);
        }
    }
}
