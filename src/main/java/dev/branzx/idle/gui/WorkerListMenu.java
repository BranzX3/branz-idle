package dev.branzx.idle.gui;

import dev.branzx.idle.worker.Rarity;
import dev.branzx.idle.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one roster of worker contracts, in two modes.
 *
 * <p>Browsing the bag and picking a worker for a Node or the Fuse Station used
 * to be two near-identical screens; they differed only in where the list came
 * from and what a click did. Both are expressed here so the roster looks and
 * pages the same wherever the player meets it.
 *
 * <p>A picked worker is reported by UUID, never by inventory position, so the
 * caller re-locates and consumes the real item at action time. That keeps the
 * screen click-driven with no item movement and no swap or loss risk.
 */
public final class WorkerListMenu extends Menu {

    public record Choice(UUID workerUuid, WorkerRecord record) {
    }

    private final GuiManager gui;
    private final int page;

    /** Null in bag mode; set in pick mode. */
    private final Consumer<Choice> onPick;
    private final Runnable onBack;
    private final String headingKey;
    private final Rarity filter;
    private final UUID exclude;

    private WorkerListMenu(Player viewer, GuiManager gui, int page, Consumer<Choice> onPick,
                           Runnable onBack, String headingKey, Rarity filter, UUID exclude) {
        super(viewer);
        this.gui = gui;
        this.page = Math.max(0, page);
        this.onPick = onPick;
        this.onBack = onBack;
        this.headingKey = headingKey;
        this.filter = filter;
        this.exclude = exclude;
    }

    /** Browse mode: the player's own bag, with the bag tools on the bottom bar. */
    public static WorkerListMenu bag(Player viewer, GuiManager gui, int page) {
        return new WorkerListMenu(viewer, gui, page, null,
                () -> gui.openWorkers(viewer), "menu.workers.bag.title", null, null);
    }

    /**
     * Pick mode: bag contracts plus any loose in the player's inventory, since
     * both are assignable.
     *
     * @param filter  only offer this rarity, or null for any
     * @param exclude a worker already chosen elsewhere in the flow, or null
     */
    public static WorkerListMenu picker(Player viewer, GuiManager gui, Rarity filter,
                                        UUID exclude, String headingKey,
                                        Consumer<Choice> onPick, Runnable onBack) {
        return new WorkerListMenu(viewer, gui, 0, onPick, onBack, headingKey, filter, exclude);
    }

    private boolean picking() {
        return onPick != null;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(Lang.get(headingKey), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        List<Choice> choices = scan();

        set(SUMMARY_SLOT, summary(choices.size()));

        int start = page * CONTENT_GRID.length;
        for (int i = 0; i < CONTENT_GRID.length && start + i < choices.size(); i++) {
            Choice choice = choices.get(start + i);
            set(CONTENT_GRID[i], card(choice.record()), event -> {
                if (picking()) {
                    onPick.accept(choice);
                } else {
                    gui.openWorkerDetail(viewer, choice.workerUuid());
                }
            });
        }
        if (choices.isEmpty()) {
            set(CONTENT_GRID[10], Icon.of(Material.BARRIER)
                    .name(Lang.get(filter == null ? "menu.workers.list.empty"
                            : "menu.workers.list.empty-filtered",
                            "rarity", filter == null ? "" : filter.name()), NamedTextColor.RED)
                    .lore(Lang.get("menu.workers.list.empty-hint"), NamedTextColor.GRAY)
                    .build());
        }

        if (!picking()) {
            drawBagTools();
        }
        navBar(Lang.get("menu.workers.back"), onBack);
        pager(page, pageCount(choices.size()),
                target -> new WorkerListMenu(viewer, gui, target, onPick, onBack,
                        headingKey, filter, exclude).open());
    }

    private ItemStack summary(int shown) {
        int used = gui.workerStore().bagCount(viewer.getUniqueId());
        int cap = gui.workerService().bagCapacity(viewer.getUniqueId());
        return Icon.of(Material.CHEST_MINECART)
                .name(Lang.get("menu.workers.list.summary", "count", shown),
                        NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Ui.bar(Lang.get("menu.workers.list.bar-bag"),
                                cap == 0 ? 0 : used / (double) cap,
                                used >= cap ? NamedTextColor.RED : NamedTextColor.AQUA,
                                used + "/" + cap),
                        Lang.line(picking() ? "menu.workers.list.pick-hint"
                                : "menu.workers.list.browse-hint", NamedTextColor.GRAY)))
                .build();
    }

    private ItemStack card(WorkerRecord worker) {
        List<Component> lore = new ArrayList<>(gui.workerService().workerLore(worker));
        lore.add(Lang.click(picking() ? "menu.workers.list.click-pick"
                : "menu.workers.list.click-manage"));
        return Icon.head(gui.skinHeadCache(), worker.getSkin())
                .name(worker.getName(), worker.getRarity().color())
                .loreComponents(lore).build();
    }

    private void drawBagTools() {
        set(navRow() + 1, Icon.of(Material.HOPPER)
                .name(Lang.get("menu.workers.bag.deposit.name"), NamedTextColor.GREEN)
                .lore(Lang.get("menu.workers.bag.deposit.hint"), NamedTextColor.GRAY).build(),
                event -> depositAll());
        double expandCost = gui.plugin().getConfig().getDouble("workers.bag.expand-cost", 5000);
        int step = gui.plugin().getConfig().getInt("workers.bag.expand-step", 9);
        setConfirm(navRow() + 7, Icon.of(Material.ENDER_CHEST)
                .name(Lang.get("menu.workers.bag.expand.name", "step", step),
                        NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.workers.bag.expand.cost", NamedTextColor.GOLD,
                                "cost", Ui.num(expandCost)),
                        Lang.click("menu.workers.bag.expand.click")))
                .build(), this::expand);
    }

    /**
     * Bag contracts first, then loose item-form contracts in the inventory.
     * Bag mode stops at the bag so it stays a view of the bag itself.
     */
    private List<Choice> scan() {
        List<Choice> choices = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (WorkerRecord record : gui.workerStore().getBag(viewer.getUniqueId())) {
            if (accept(record) && seen.add(record.getWorkerUuid())) {
                choices.add(new Choice(record.getWorkerUuid(), record));
            }
        }
        if (picking()) {
            for (ItemStack item : viewer.getInventory().getContents()) {
                WorkerRecord record = gui.workerService().fromItem(item);
                if (record != null && WorkerRecord.STATE_ITEM.equals(record.getState())
                        && accept(record) && seen.add(record.getWorkerUuid())) {
                    choices.add(new Choice(record.getWorkerUuid(), record));
                }
            }
        }
        choices.sort((x, y) -> {
            int rarity = y.record().getRarity().ordinal() - x.record().getRarity().ordinal();
            return rarity != 0 ? rarity : y.record().getLevel() - x.record().getLevel();
        });
        return choices;
    }

    private boolean accept(WorkerRecord record) {
        if (filter != null && record.getRarity() != filter) {
            return false;
        }
        return exclude == null || !record.getWorkerUuid().equals(exclude);
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
            viewer.sendMessage(Component.text(
                    Lang.get("menu.workers.bag.deposited", "count", deposited),
                    NamedTextColor.GREEN));
        }
        redraw();
    }

    private void expand() {
        String error = gui.workerService().expandBag(viewer.getUniqueId());
        viewer.sendMessage(Component.text(
                error == null ? Lang.get("menu.workers.bag.expand.done") : error,
                error == null ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
    }

    /** Removes one worker item by UUID from the player's inventory; true if found. */
    public static boolean consumeFromInventory(Player player, GuiManager gui, UUID workerUuid) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            WorkerRecord record = gui.workerService().fromItem(contents[i]);
            if (record != null && record.getWorkerUuid().equals(workerUuid)) {
                ItemStack item = contents[i];
                if (item.getAmount() <= 1) {
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - 1);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected Material frameMaterial() {
        return Material.MAGENTA_STAINED_GLASS_PANE;
    }
}
