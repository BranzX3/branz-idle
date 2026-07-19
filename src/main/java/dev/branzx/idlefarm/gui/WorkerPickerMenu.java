package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.worker.Rarity;
import dev.branzx.idlefarm.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Click-to-select list of the player's item-form worker contracts (scanned
 * from their inventory — workers are physical items). Purely click-driven:
 * no item movement, so there is no placeholder-swap or item-loss risk.
 * A picked worker is reported by UUID so the caller can re-locate and
 * consume the actual item safely at action time.
 */
public final class WorkerPickerMenu extends Menu {

    public record Choice(UUID workerUuid, WorkerRecord record) {
    }

    private static final int PAGE_SIZE = 45;

    private final GuiManager gui;
    private final Rarity filter; // null = any rarity
    private final UUID exclude;  // already-picked worker to hide (fuse slot A), nullable
    private final String heading;
    private final Consumer<Choice> onPick;
    private final Runnable onBack;
    private final int page;

    public WorkerPickerMenu(Player viewer, GuiManager gui, Rarity filter, UUID exclude,
                            String heading, Consumer<Choice> onPick, Runnable onBack) {
        this(viewer, gui, filter, exclude, heading, onPick, onBack, 0);
    }

    private WorkerPickerMenu(Player viewer, GuiManager gui, Rarity filter, UUID exclude,
                             String heading, Consumer<Choice> onPick, Runnable onBack, int page) {
        super(viewer);
        this.gui = gui;
        this.filter = filter;
        this.exclude = exclude;
        this.heading = heading;
        this.onPick = onPick;
        this.onBack = onBack;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text(heading, NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        List<Choice> choices = scan();
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < choices.size(); i++) {
            Choice choice = choices.get(start + i);
            set(i, gui.workerService().createItem(choice.record()), e -> onPick.accept(choice));
        }
        if (choices.isEmpty()) {
            set(22, Icon.of(Material.BARRIER)
                    .name(filter == null ? "No workers in inventory"
                            : "No " + filter + " workers in inventory", NamedTextColor.RED)
                    .lore("Hire or fuse to get workers", NamedTextColor.GRAY).build());
        }

        for (int i = 45; i < 54; i++) {
            set(i, Icon.filler());
        }
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("Previous", NamedTextColor.YELLOW).build(),
                    e -> new WorkerPickerMenu(viewer, gui, filter, exclude, heading, onPick, onBack, page - 1).open());
        }
        set(49, Icon.of(Material.BARRIER).name("Back", NamedTextColor.RED).build(), e -> onBack.run());
        if (start + PAGE_SIZE < choices.size()) {
            set(53, Icon.of(Material.ARROW).name("Next", NamedTextColor.YELLOW).build(),
                    e -> new WorkerPickerMenu(viewer, gui, filter, exclude, heading, onPick, onBack, page + 1).open());
        }
    }

    /** Item-form worker contracts currently in the player's inventory. */
    private List<Choice> scan() {
        List<Choice> choices = new ArrayList<>();
        for (ItemStack item : viewer.getInventory().getContents()) {
            WorkerRecord record = gui.workerService().fromItem(item);
            if (record == null || !WorkerRecord.STATE_ITEM.equals(record.getState())) {
                continue;
            }
            if (filter != null && record.getRarity() != filter) {
                continue;
            }
            if (exclude != null && record.getWorkerUuid().equals(exclude)) {
                continue;
            }
            choices.add(new Choice(record.getWorkerUuid(), record));
        }
        return choices;
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
}
