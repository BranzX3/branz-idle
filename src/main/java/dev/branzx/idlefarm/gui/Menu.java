package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.HashMap;
import java.util.Map;

/**
 * Base chest menu. Holds its own inventory (so the click listener can
 * identify our menus by holder), a slot -> click-handler map, and a redraw
 * hook. Menus are presentation only — handlers call services.
 */
public abstract class Menu implements InventoryHolder {

    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, ClickHandler> handlers = new HashMap<>();

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
        build();
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

    /** Standard neutral frame used by every chest menu. */
    protected void fill() {
        for (int slot = 0; slot < rows() * 9; slot++) {
            set(slot, Icon.filler());
        }
    }

    /** Standard bottom-row route back to the player hub. */
    protected void backToHub(GuiManager gui) {
        set(rows() * 9 - 5, Icon.of(Material.ARROW)
                .name("กลับหน้าหลัก", NamedTextColor.GREEN)
                .lore("Back to IdleFarm Hub", NamedTextColor.DARK_GRAY)
                .build(), event -> gui.openMainHub(viewer));
    }

    /** Standard close action, always at the middle of the bottom row. */
    protected void closeButton() {
        set(rows() * 9 - 5, Icon.of(Material.BARRIER)
                .name("ปิดเมนู", NamedTextColor.RED)
                .lore("Close", NamedTextColor.DARK_GRAY)
                .build(), event -> viewer.closeInventory());
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

    /**
     * Optional handler for clicks in the player's inventory while this menu
     * is open. Item movement remains cancelled; menus perform validated
     * transfers explicitly.
     */
    public boolean clickPlayerInventory(InventoryClickEvent event) {
        return false;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
