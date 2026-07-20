package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Base class for click-driven, service-backed chest menus. */
public abstract class Menu implements InventoryHolder {

    private static MenuRenderer renderer;
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
        inventory = Bukkit.createInventory(this, rows() * 9, title());
        redraw();
        if (renderer != null && renderer.open(this)) {
            return;
        }
        viewer.openInventory(inventory);
    }

    public static void setRenderer(MenuRenderer menuRenderer) {
        renderer = menuRenderer;
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

    protected void fill() {
        for (int slot = 0; slot < rows() * 9; slot++) {
            set(slot, Icon.filler());
        }
    }

    protected void backToHub(GuiManager gui) {
        set(rows() * 9 - 5, Icon.of(Material.ARROW)
                .name("Back to Hub", NamedTextColor.GREEN)
                .lore("Return to the IdleFarm overview", NamedTextColor.DARK_GRAY)
                .build(), event -> gui.openMainHub(viewer));
    }

    protected void closeButton() {
        set(rows() * 9 - 5, Icon.of(Material.BARRIER)
                .name("Close Menu", NamedTextColor.RED)
                .lore("Return to the game", NamedTextColor.DARK_GRAY)
                .build(), event -> viewer.closeInventory());
    }

    /** Context-preserving back action for nested flows. */
    protected void backButton(int slot, String destination, Runnable onBack) {
        set(slot, Icon.of(Material.ARROW)
                .name("Back to " + destination, NamedTextColor.GREEN)
                .lore("Return without losing your place", NamedTextColor.DARK_GRAY)
                .build(), event -> onBack.run());
    }

    public boolean click(InventoryClickEvent event) {
        ClickHandler handler = handlers.get(event.getRawSlot());
        if (handler == null) {
            return false;
        }
        handler.handle(event);
        return true;
    }

    /** Activates a normal single-click action from a non-inventory renderer. */
    public boolean activate(int slot) {
        ClickHandler handler = handlers.get(slot);
        if (handler == null) {
            return false;
        }
        handler.handle(null);
        return true;
    }

    Map<Integer, ClickHandler> actions() {
        return Map.copyOf(handlers);
    }

    Player viewer() {
        return viewer;
    }

    Component menuTitle() {
        return title();
    }

    public boolean clickPlayerInventory(InventoryClickEvent event) {
        return false;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
