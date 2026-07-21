package dev.branzx.idle.listener;

import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.service.WorkerService;
import dev.branzx.idle.worker.WorkerRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

/** Enforces account-bound Starter Worker item restrictions outside menus. */
public final class WorkerSafetyListener implements Listener {

    private final WorkerService workers;
    private final GameDesignService design;

    public WorkerSafetyListener(WorkerService workers, GameDesignService design) {
        this.workers = workers;
        this.design = design;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isStarter(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "The Starter Worker is account-bound and cannot be dropped. ",
                    NamedTextColor.RED)
                    .append(dev.branzx.idle.command.CommandLinks.run("[Open Bag]", "/idle bag")));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMove(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof dev.branzx.idle.gui.Menu) {
            return;
        }
        boolean topIsContainer = event.getView().getTopInventory().getType()
                != org.bukkit.event.inventory.InventoryType.CRAFTING;
        if (!topIsContainer) return;
        boolean placingCursorIntoTop = event.getRawSlot() < event.getView().getTopInventory().getSize()
                && isStarter(event.getCursor());
        boolean shiftMovingFromPlayer = event.isShiftClick()
                && event.getClickedInventory() == event.getView().getBottomInventory()
                && isStarter(event.getCurrentItem());
        if (placingCursorIntoTop || shiftMovingFromPlayer) {
            event.setCancelled(true);
            event.getWhoClicked().sendMessage(Component.text(
                    "The Starter Worker cannot be stored or transferred. ",
                    NamedTextColor.RED)
                    .append(dev.branzx.idle.command.CommandLinks.run("[Open Bag]", "/idle bag")));
        }
    }

    private boolean isStarter(ItemStack item) {
        WorkerRecord worker = workers.fromItem(item);
        return worker != null && design.isStarterWorker(worker.getWorkerUuid());
    }
}
