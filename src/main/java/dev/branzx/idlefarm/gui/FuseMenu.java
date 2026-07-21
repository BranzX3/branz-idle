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
 * Picked workers stay as items in the inventory and are only consumed when the
 * Fuse button rolls.
 *
 * <p>The screen is split: the left half is the fuse apparatus (two slots, the
 * preview, the button) and the right half lists the workers that can actually
 * go in it. Choosing no longer bounces through a separate picker screen, and
 * the candidate list is filtered to what will really fuse — once slot A holds
 * a Rare, only other Rares are offered, so a click can never produce an error.
 */
public final class FuseMenu extends Menu {

    // Everything lives inside the glass border, so columns 1-7 only. The left
    // apparatus takes columns 1-3, a glass rule holds column 4, and the
    // candidate pool takes columns 5-7.

    /** Left: pick two, read the odds, press the button — top to bottom. */
    private static final int SLOT_A = 19;
    private static final int PLUS = 20;
    private static final int SLOT_B = 21;
    private static final int INFO = 29;
    private static final int FUSE_BTN = 38;

    /** Column 4: a rule that makes the two halves unmistakable. */
    private static final int[] DIVIDER = {13, 22, 31, 40};

    /** Right: the workers that can actually go in, three per row. */
    private static final int[] CANDIDATES = {
            14, 15, 16,
            23, 24, 25,
            32, 33, 34,
            41, 42, 43};

    private final GuiManager gui;
    private UUID pickedA;
    private UUID pickedB;

    public FuseMenu(Player viewer, GuiManager gui) {
        super(viewer);
        this.gui = gui;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get("menu.fuse.title"), NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        WorkerRecord a = resolve(pickedA);
        WorkerRecord b = resolve(pickedB);
        // Drop stale picks (item left inventory / got assigned elsewhere).
        if (pickedA != null && a == null) {
            pickedA = null;
        }
        if (pickedB != null && b == null) {
            pickedB = null;
        }

        set(SUMMARY_SLOT, Icon.of(Material.SMITHING_TABLE)
                .name(Lang.get("menu.fuse.title"), NamedTextColor.LIGHT_PURPLE)
                .loreComponents(List.of(
                        Lang.line("menu.fuse.summary", NamedTextColor.GRAY),
                        Lang.line("menu.fuse.summary-filter", NamedTextColor.DARK_GRAY)))
                .build());

        drawDivider();
        drawSlot(SLOT_A, a, "menu.fuse.slot-a");
        set(PLUS, Icon.of(Material.NETHER_STAR)
                .name(Lang.get("menu.fuse.plus"), NamedTextColor.DARK_GRAY).build());
        drawSlot(SLOT_B, b, "menu.fuse.slot-b");
        drawCandidates(a, b);
        refreshInfo(a, b);

        navBar(Lang.get("menu.workers.back"), () -> gui.openWorkers(viewer));
    }

    /**
     * A labelled glass rule down the middle. The border rule says glass is
     * never background, and this is not: it is the thing that tells the player
     * the two halves are different tools rather than one long list.
     */
    private void drawDivider() {
        for (int slot : DIVIDER) {
            set(slot, Icon.of(Material.PURPLE_STAINED_GLASS_PANE)
                    .name(Lang.get("menu.fuse.divider"), NamedTextColor.DARK_PURPLE)
                    .build());
        }
    }

    private void drawSlot(int slot, WorkerRecord picked, String labelKey) {
        if (picked == null) {
            set(slot, Icon.of(Material.ITEM_FRAME)
                    .name(Lang.get(labelKey), NamedTextColor.AQUA)
                    .lore(Lang.get("menu.fuse.slot-empty"), NamedTextColor.GRAY).build());
            return;
        }
        List<Component> lore = new ArrayList<>(gui.workerService().workerLore(picked));
        lore.add(Lang.click("menu.fuse.slot-clear"));
        set(slot, Icon.head(gui.skinHeadCache(), picked.getSkin())
                .name(picked.getName(), picked.getRarity().color())
                .loreComponents(lore).build(), event -> {
            if (slot == SLOT_A) {
                pickedA = null;
                pickedB = null; // clearing A drops B's rarity lock
            } else {
                pickedB = null;
            }
            redraw();
        });
    }

    /**
     * Candidates are whatever could still legally go into the next empty slot:
     * anything fusable at all while A is empty, then only A's rarity.
     */
    private void drawCandidates(WorkerRecord a, WorkerRecord b) {
        if (a != null && b != null) {
            // Both slots full. Say so, rather than leaving half the screen
            // blank and letting it read as a loading failure.
            set(CANDIDATES[1], Icon.of(Material.LIME_DYE)
                    .name(Lang.get("menu.fuse.ready.name"), NamedTextColor.GREEN)
                    .loreComponents(List.of(
                            Lang.line("menu.fuse.ready.hint", NamedTextColor.GRAY),
                            Lang.line("menu.fuse.ready.clear", NamedTextColor.DARK_GRAY)))
                    .build());
            return;
        }
        Rarity required = a == null ? null : a.getRarity();
        List<WorkerRecord> pool = candidates(required);
        if (pool.isEmpty()) {
            set(CANDIDATES[1], Icon.of(Material.BARRIER)
                    .name(Lang.get("menu.fuse.no-candidates"), NamedTextColor.RED)
                    .lore(Lang.get("menu.fuse.no-candidates-hint"), NamedTextColor.GRAY)
                    .build());
            return;
        }
        if (pool.size() > CANDIDATES.length) {
            // No paging here; be honest that the list is truncated.
            set(CANDIDATES[CANDIDATES.length - 1], Icon.of(Material.PAPER)
                    .name(Lang.get("menu.fuse.more",
                            "count", pool.size() - CANDIDATES.length + 1),
                            NamedTextColor.GRAY)
                    .lore(Lang.get("menu.fuse.more-hint"), NamedTextColor.DARK_GRAY).build());
        }
        int shown = pool.size() > CANDIDATES.length
                ? CANDIDATES.length - 1 : pool.size();
        for (int i = 0; i < shown; i++) {
            WorkerRecord worker = pool.get(i);
            List<Component> lore = new ArrayList<>(gui.workerService().workerLore(worker));
            lore.add(Lang.click("menu.fuse.pick"));
            set(CANDIDATES[i], Icon.head(gui.skinHeadCache(), worker.getSkin())
                    .name(worker.getName(), worker.getRarity().color())
                    .loreComponents(lore).build(), event -> {
                if (pickedA == null) {
                    pickedA = worker.getWorkerUuid();
                } else {
                    pickedB = worker.getWorkerUuid();
                }
                redraw();
            });
        }
    }

    /**
     * Bag plus loose inventory contracts that are actually fusable: matching
     * the required rarity if one is set, never already picked, and never a
     * rarity with nothing above it.
     */
    private List<WorkerRecord> candidates(Rarity required) {
        List<WorkerRecord> pool = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        List<WorkerRecord> sources = new ArrayList<>(
                gui.workerStore().getBag(viewer.getUniqueId()));
        for (var item : viewer.getInventory().getContents()) {
            WorkerRecord record = gui.workerService().fromItem(item);
            if (record != null && WorkerRecord.STATE_ITEM.equals(record.getState())) {
                sources.add(record);
            }
        }
        for (WorkerRecord record : sources) {
            if (!seen.add(record.getWorkerUuid())) {
                continue;
            }
            if (record.getWorkerUuid().equals(pickedA)
                    || record.getWorkerUuid().equals(pickedB)) {
                continue;
            }
            if (record.getRarity().next() == null) {
                continue; // nothing to fuse up into
            }
            if (required != null && record.getRarity() != required) {
                continue;
            }
            pool.add(record);
        }
        pool.sort((x, y) -> {
            int rarity = y.getRarity().ordinal() - x.getRarity().ordinal();
            return rarity != 0 ? rarity : y.getLevel() - x.getLevel();
        });
        return pool;
    }

    private void refreshInfo(WorkerRecord a, WorkerRecord b) {
        List<Component> lore = new ArrayList<>();
        String error = validate(a, b);
        if (error != null) {
            lore.add(Ui.line(error, NamedTextColor.GRAY));
            set(INFO, Icon.of(Material.GRAY_DYE)
                    .name(Lang.get("menu.fuse.not-ready"), NamedTextColor.GRAY)
                    .loreComponents(lore).build());
            set(FUSE_BTN, Icon.of(Material.GRAY_DYE)
                    .name(Lang.get("menu.fuse.button-locked"), NamedTextColor.RED).build());
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
        set(INFO, Icon.of(Material.SMITHING_TABLE)
                .name(Lang.get("menu.fuse.preview"), NamedTextColor.LIGHT_PURPLE)
                .loreComponents(lore).build());
        setDangerConfirm(FUSE_BTN, Icon.of(Material.NETHER_STAR)
                .name(Lang.get("menu.fuse.button", "chance", Math.round(chance * 100)),
                        NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.fuse.base", NamedTextColor.GRAY,
                                "name", a.getName()),
                        Lang.line("menu.fuse.duplicate", NamedTextColor.RED,
                                "name", b.getName()),
                        Lang.line("menu.fuse.exp", NamedTextColor.GRAY),
                        Lang.click("menu.fuse.roll")))
                .build(), () -> doFuse(a, b));
    }

    private String validate(WorkerRecord a, WorkerRecord b) {
        if (a == null || b == null) {
            return Lang.get("menu.fuse.need-two");
        }
        if (a.getRarity() != b.getRarity()) {
            return Lang.get("menu.fuse.same-rarity");
        }
        if (a.getRarity().next() == null) {
            return Lang.get("menu.fuse.max-rarity");
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
            WorkerListMenu.consumeFromInventory(viewer, gui, a.getWorkerUuid());
        }
        if (bItem && gui.workerStore().get(b.getWorkerUuid()) == null) {
            WorkerListMenu.consumeFromInventory(viewer, gui, b.getWorkerUuid());
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

    @Override
    protected Material frameMaterial() {
        return Material.MAGENTA_STAINED_GLASS_PANE;
    }
}
