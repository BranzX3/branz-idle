package dev.branzx.idle.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Base class for click-driven, service-backed chest menus.
 *
 * <p>Layout is standardised here rather than per screen: every menu keeps an
 * empty background, a read-only summary card in slot 4, and a bottom bar with
 * Back on the left and Close in the centre. Use {@link #navBar}, {@link #pager}
 * and {@link #tabBar} instead of placing those slots by hand.
 */
public abstract class Menu implements InventoryHolder {

    /** How long a pending confirmation survives without a follow-up click. */
    private static final long CONFIRM_TIMEOUT_MILLIS = 5_000L;

    /** Read-only summary card position, shared by every screen. */
    protected static final int SUMMARY_SLOT = 4;

    /**
     * Standard content area for paged lists: four rows of seven, inset one
     * column each side so the grid reads as a block and never touches the
     * header row or the bottom bar.
     */
    protected static final int[] CONTENT_GRID = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    /** Number of pages needed to show {@code total} items in {@link #CONTENT_GRID}. */
    protected static int pageCount(int total) {
        return Math.max(1, (total + CONTENT_GRID.length - 1) / CONTENT_GRID.length);
    }

    private static MenuRenderer renderer;
    protected final Player viewer;
    private Inventory inventory;
    private final Map<Integer, ClickHandler> handlers = new HashMap<>();

    // Inline confirmation state. Lives on the instance so it survives redraw()
    // on Java and the re-open that BedrockMenuRenderer performs after a click.
    private int pendingSlot = -1;
    private int pendingStage;
    private long pendingSince;

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
        drawFrame();
    }

    /**
     * Glass border around the outside of the screen, drawn after {@link #build}
     * so it can only ever land on slots the screen left empty — content always
     * wins its slot no matter what order a subclass draws in.
     *
     * <p>Only the outer ring is framed; the middle stays empty so a half-filled
     * content area does not turn into a field of glass.
     */
    private void drawFrame() {
        Material material = frameMaterial();
        if (material == null) {
            return;
        }
        ItemStack pane = Icon.of(material).name(" ", NamedTextColor.DARK_GRAY).build();
        int lastRow = rows() - 1;
        for (int row = 0; row <= lastRow; row++) {
            for (int column = 0; column < 9; column++) {
                boolean edge = row == 0 || row == lastRow || column == 0 || column == 8;
                if (!edge) {
                    continue;
                }
                int slot = row * 9 + column;
                ItemStack current = inventory.getItem(slot);
                if (current == null || current.getType().isAir()) {
                    inventory.setItem(slot, pane);
                }
            }
        }
    }

    /**
     * Glass used for the border. Screens override this to match their own
     * accent colour; returning null leaves the screen unframed, which is what
     * a full-width content grid wants.
     */
    protected Material frameMaterial() {
        return Material.GRAY_STAINED_GLASS_PANE;
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

    /** First slot of the bottom row; the whole nav bar is positioned from here. */
    protected int navRow() {
        return rows() * 9 - 9;
    }

    /**
     * Standard bottom bar: Back on the left, Close in the centre. Pass a null
     * action for screens with nowhere to go back to.
     */
    protected void navBar(String destination, Runnable onBack) {
        if (onBack != null) {
            set(navRow(), Icon.of(Material.ARROW)
                    .name(Lang.get("menu.common.back.name", "destination", destination),
                            NamedTextColor.GREEN)
                    .lore(Lang.get("menu.common.back.hint"), NamedTextColor.DARK_GRAY)
                    .build(), event -> onBack.run());
        }
        set(navRow() + 4, Icon.of(Material.BARRIER)
                .name(Lang.get("menu.common.close.name"), NamedTextColor.RED)
                .lore(Lang.get("menu.common.close.hint"), NamedTextColor.DARK_GRAY)
                .build(), event -> viewer.closeInventory());
    }

    /** Close only, for the home screen itself and other dead-end surfaces. */
    protected void navBar() {
        navBar(null, null);
    }

    /** Back to the Hub, for screens opened straight off the home screen. */
    protected void navBarToHub(GuiManager gui) {
        navBar(Lang.get("menu.common.hub"), () -> gui.openMainHub(viewer));
    }

    /**
     * Back action at an explicit slot, for nested flows that sit inside another
     * screen's layout and cannot use the standard bottom bar.
     */
    protected void backButton(int slot, String destination, Runnable onBack) {
        set(slot, Icon.of(Material.ARROW)
                .name(Lang.get("menu.common.back.name", "destination", destination),
                        NamedTextColor.GREEN)
                .lore(Lang.get("menu.common.back.hint"), NamedTextColor.DARK_GRAY)
                .build(), event -> onBack.run());
    }

    /**
     * Page arrows flanking Close, with a page counter in the lore. Arrows are
     * omitted at the ends so a dead button never sits next to a live one.
     */
    protected void pager(int page, int pageCount, IntConsumer onGoto) {
        if (pageCount <= 1) {
            return;
        }
        String counter = Lang.get("menu.common.page",
                "page", page + 1, "total", pageCount);
        if (page > 0) {
            set(navRow() + 3, Icon.of(Material.ARROW)
                    .name(Lang.get("menu.common.prev"), NamedTextColor.YELLOW)
                    .lore(counter, NamedTextColor.GRAY)
                    .build(), event -> onGoto.accept(page - 1));
        }
        if (page + 1 < pageCount) {
            set(navRow() + 5, Icon.of(Material.ARROW)
                    .name(Lang.get("menu.common.next"), NamedTextColor.YELLOW)
                    .lore(counter, NamedTextColor.GRAY)
                    .build(), event -> onGoto.accept(page + 1));
        }
    }

    /** One tab of a {@link #tabBar}: label key, icon, and how to open it. */
    public record Tab(String nameKey, Material material, Runnable open) {
    }

    /**
     * Top-left tab strip for screens that are several views of one subject.
     * Left-aligned rather than centred so it cannot collide with the
     * {@link #SUMMARY_SLOT} card, and so a fourth tab still fits.
     *
     * <p>The active tab is inert: clicking where you already are should do
     * nothing rather than rebuild the screen underneath you.
     */
    protected void tabBar(List<Tab> tabs, int activeIndex) {
        for (int index = 0; index < tabs.size() && index < SUMMARY_SLOT; index++) {
            Tab tab = tabs.get(index);
            boolean active = index == activeIndex;
            ItemStack icon = Icon.of(active ? tab.material() : Material.GRAY_DYE)
                    .name(Lang.get(tab.nameKey()),
                            active ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .lore(Lang.get(active ? "menu.common.tab-active"
                            : "menu.common.tab-inactive"), NamedTextColor.DARK_GRAY)
                    .build();
            set(index, icon, active ? null : event -> tab.open().run());
        }
    }

    /** The three progression views, shared by every screen showing the strip. */
    protected List<Tab> progressTabs(GuiManager gui) {
        return List.of(
                new Tab("menu.progress.tab.overview", Material.WRITABLE_BOOK,
                        () -> gui.openProgress(viewer)),
                new Tab("menu.progress.tab.chronicle", Material.WRITTEN_BOOK,
                        () -> new ChronicleMenu(viewer, gui, 0).open()),
                new Tab("menu.progress.tab.seasonal", Material.CLOCK,
                        () -> new SeasonalChronicleMenu(viewer, gui).open()));
    }

    /**
     * A button that asks for a second click before it acts. The first click
     * swaps the icon for a green dye; the second runs the action.
     */
    protected void setConfirm(int slot, ItemStack idle, Runnable action) {
        setStagedConfirm(slot, idle, 1, action);
    }

    /**
     * Like {@link #setConfirm} but for actions that cannot be undone: the first
     * click shows a red warning, and only the third click acts.
     */
    protected void setDangerConfirm(int slot, ItemStack idle, Runnable action) {
        setStagedConfirm(slot, idle, 2, action);
    }

    private void setStagedConfirm(int slot, ItemStack idle, int stages, Runnable action) {
        if (!isPending(slot)) {
            set(slot, idle, event -> {
                pendingSlot = slot;
                pendingStage = 1;
                pendingSince = System.currentTimeMillis();
                redraw();
            });
            return;
        }
        int stage = pendingStage;
        boolean last = stage >= stages;
        set(slot, promptIcon(idle, last), event -> {
            if (!isPending(slot)) {
                // Expired between the redraw and the click: make them start over
                // rather than acting on a prompt they may not have read.
                clearPending();
                redraw();
                return;
            }
            if (last) {
                clearPending();
                action.run();
            } else {
                pendingStage = stage + 1;
                pendingSince = System.currentTimeMillis();
                redraw();
            }
        });
    }

    /** Keeps the original button's name so the player still knows what they hit. */
    private ItemStack promptIcon(ItemStack idle, boolean readyToAct) {
        ItemMeta meta = idle.getItemMeta();
        Component name = meta == null || meta.displayName() == null
                ? Component.text(Lang.get("menu.common.action"))
                : meta.displayName();
        return Icon.of(readyToAct ? Material.LIME_DYE : Material.RED_DYE)
                .nameComponent(name)
                .loreComponents(List.of(
                        Ui.line(Lang.get(readyToAct
                                        ? "menu.common.confirm" : "menu.common.danger"),
                                readyToAct ? NamedTextColor.GREEN : NamedTextColor.RED),
                        Ui.line(Lang.get("menu.common.confirm-expiry"),
                                NamedTextColor.DARK_GRAY)))
                .build();
    }

    private boolean isPending(int slot) {
        return pendingSlot == slot
                && System.currentTimeMillis() - pendingSince <= CONFIRM_TIMEOUT_MILLIS;
    }

    private void clearPending() {
        pendingSlot = -1;
        pendingStage = 0;
        pendingSince = 0L;
    }

    public boolean click(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (pendingSlot != -1 && pendingSlot != slot) {
            // Any other click is an implicit "no".
            clearPending();
        }
        ClickHandler handler = handlers.get(slot);
        if (handler == null) {
            return false;
        }
        handler.handle(event);
        return true;
    }

    /** Activates a normal single-click action from a non-inventory renderer. */
    public boolean activate(int slot) {
        if (pendingSlot != -1 && pendingSlot != slot) {
            clearPending();
        }
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
