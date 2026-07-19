package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Base chest menu. Holds its own inventory (so the click listener can
 * identify our menus by holder), a slot -> click-handler map, and a redraw
 * hook. Menus are presentation only — handlers call services.
 */
public abstract class Menu implements InventoryHolder {

    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, ClickHandler> handlers = new HashMap<>();
    /** Slots where the player may freely place/take items (e.g. fuse inputs). */
    private final Set<Integer> inputSlots = new HashSet<>();

    @FunctionalInterface
    public interface ClickHandler {
        void handle(InventoryClickEvent event);
    }

    protected Menu(Player viewer) {
        this.viewer = viewer;
    }

    protected abstract int rows();

    protected abstract Component title();

    protected abstract void build();

    public void open() {
        this.inventory = Bukkit.createInventory(this, rows() * 9, title());
        redraw();
        viewer.openInventory(inventory);
    }

    public void redraw() {
        inventory.clear();
        handlers.clear();
        inputSlots.clear();
        build();
    }

    /** Marks a slot as a free input slot (item placement allowed). */
    protected void markInputSlot(int slot) {
        inputSlots.add(slot);
    }

    public boolean isInputSlot(int rawSlot) {
        return inputSlots.contains(rawSlot);
    }

    public boolean hasInputSlots() {
        return !inputSlots.isEmpty();
    }

    /** Override to recompute display after the player edits an input slot. */
    public void onInputChanged() {
    }

    public ItemStack itemAt(int slot) {
        return inventory.getItem(slot);
    }

    public void setRaw(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    /** Override to react after the player closes the menu (e.g. return items). */
    public void onClose() {
    }

    protected void set(int slot, ItemStack item, ClickHandler handler) {
        inventory.setItem(slot, item);
        if (handler != null) {
            handlers.put(slot, handler);
        }
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** Called by the listener; returns true if the click was on a mapped slot. */
    public boolean click(InventoryClickEvent event) {
        ClickHandler handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.handle(event);
            return true;
        }
        return false;
    }

    /** Whether a click in this menu's own inventory area is allowed to move items. */
    public boolean allowItemMovement() {
        return false;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
