package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Paged warehouse view with explicit, cross-platform item actions. */
public final class WarehouseMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final GuiManager gui;
    private final UUID owner;
    private final int page;

    public WarehouseMenu(Player viewer, GuiManager gui, UUID owner, int page) {
        super(viewer);
        this.gui = gui;
        this.owner = owner;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    /**
     * Unframed on purpose: the item grid owns slots 0-44, so a border would
     * only appear in whatever gaps a half-full page happens to leave, which
     * reads as broken rather than as a frame.
     */
    @Override
    protected Material frameMaterial() {
        return null;
    }

    @Override
    protected Component title() {
        // Capacity is read off the Storage icon on the bottom bar; the title
        // only ever says which screen you are on.
        return Component.text(Lang.get("menu.warehouse.title"), NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        if (!owner.equals(viewer.getUniqueId())) {
            viewer.closeInventory();
            viewer.sendMessage(Component.text("You cannot open another player's Warehouse.",
                    NamedTextColor.RED));
            return;
        }
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(gui.warehouseService().getContents(owner).entrySet());
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < entries.size(); i++) {
            Map.Entry<String, Integer> entry = entries.get(start + i);
            Material material = Material.matchMaterial(entry.getKey());
            boolean custom = material == null && gui.dropTableService() != null
                    && gui.dropTableService().isCustomMaterial(entry.getKey());
            if (material == null && !custom) continue;
            Material iconMaterial = custom ? Material.PRISMARINE_SHARD : material;
            set(i, Icon.of(iconMaterial)
                    .name(prettify(entry.getKey()), NamedTextColor.WHITE)
                    .amount(Math.min(64, entry.getValue()))
                    .loreComponents(List.of(
                            Ui.line("Stored ×" + Ui.num(entry.getValue()), NamedTextColor.GOLD),
                            Ui.divider(),
                            Ui.line("Click: withdrawal options", NamedTextColor.AQUA),
                            Ui.line("Shift-click: withdraw 1 stack", NamedTextColor.DARK_AQUA))).build(),
                    e -> {
                        if (e != null && e.isShiftClick()) {
                            withdraw(entry.getKey(), material, false);
                        } else {
                            new ItemActionsMenu(entry.getKey(), material).open();
                        }
                    });
        }

        // This screen is the one exception to the slot-4 summary card: the item
        // grid needs all 45 slots, so Vault/Silo live in the title and in the
        // Storage icon on the bottom bar instead.
        navBarToHub(gui);
        set(navRow() + 1, Icon.of(Material.HOPPER)
                .name(Lang.get("menu.warehouse.deposit.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.warehouse.deposit.hint", NamedTextColor.GRAY),
                        Lang.line("menu.warehouse.deposit.cross-platform",
                                NamedTextColor.DARK_GRAY)))
                .build(), e -> new DepositMenu().open());
        setConfirm(navRow() + 2, Icon.of(Material.HOPPER_MINECART)
                .name(Lang.get("menu.warehouse.deposit-all.name"), NamedTextColor.GREEN)
                .loreComponents(List.of(
                        Lang.line("menu.warehouse.deposit-all.hint", NamedTextColor.GRAY),
                        Lang.line("menu.warehouse.deposit-all.custom",
                                NamedTextColor.DARK_GRAY),
                        Lang.click("menu.warehouse.deposit-all.click")))
                .build(), () -> {
                    depositAll();
                    redraw();
                });
        // Two bars, because the two pools fill at completely different speeds:
        // a player reading one merged number cannot tell which one is stopping
        // their production.
        int vault = gui.warehouseService().vaultTotal(owner);
        int vaultCapacity = gui.warehouseService().vaultCapacity(owner);
        int silo = gui.warehouseService().siloTotal(owner);
        int siloCapacity = gui.warehouseService().siloCapacity(owner);
        boolean anyFull = vault >= vaultCapacity || silo >= siloCapacity;
        set(navRow() + 6, Icon.of(Material.BOOK)
                .name(Lang.get("menu.warehouse.storage.name"),
                        anyFull ? NamedTextColor.RED : NamedTextColor.GOLD)
                .loreComponents(List.of(
                        Ui.bar(Lang.get("menu.warehouse.bar-vault"),
                                vaultCapacity == 0 ? 0 : vault / (double) vaultCapacity,
                                vault >= vaultCapacity ? NamedTextColor.RED : NamedTextColor.GOLD,
                                Ui.num(vault) + "/" + Ui.num(vaultCapacity)),
                        Lang.line("menu.warehouse.vault-holds", NamedTextColor.DARK_GRAY),
                        Ui.bar(Lang.get("menu.warehouse.bar-silo"),
                                siloCapacity == 0 ? 0 : silo / (double) siloCapacity,
                                silo >= siloCapacity ? NamedTextColor.RED : NamedTextColor.AQUA,
                                Ui.num(silo) + "/" + Ui.num(siloCapacity)),
                        Lang.line("menu.warehouse.silo-holds", NamedTextColor.DARK_GRAY),
                        Lang.line("menu.warehouse.storage.hint", NamedTextColor.GRAY)))
                .build());
        double expandCost = gui.plugin().getConfig().getDouble("warehouse.expand-cost", 5000);
        int expandStep = gui.plugin().getConfig().getInt("warehouse.expand-step", 1000);
        int siloStep = gui.plugin().getConfig().getInt("warehouse.silo.expand-step", 20_000);
        setConfirm(navRow() + 7, Icon.of(Material.ENDER_CHEST)
                .name(Lang.get("menu.warehouse.expand.name"), NamedTextColor.AQUA)
                .loreComponents(List.of(
                        Lang.line("menu.warehouse.expand.vault", NamedTextColor.GOLD,
                                "amount", Ui.num(expandStep)),
                        Lang.line("menu.warehouse.expand.silo", NamedTextColor.AQUA,
                                "amount", Ui.num(siloStep)),
                        Lang.line("menu.warehouse.expand.cost", NamedTextColor.GOLD,
                                "cost", formatAmount(expandCost)),
                        Lang.click("menu.warehouse.expand.click"))).build(),
                this::expand);

        pager(page, Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE),
                target -> new WarehouseMenu(viewer, gui, owner, target).open());
    }

    private void promptExact(String key, Material material) {
        int have = gui.warehouseService().getContents(owner).getOrDefault(key.toUpperCase(Locale.ROOT), 0);
        gui.chatPrompt().requestNumber(viewer,
                "How many " + prettify(key) + " to withdraw? (have " + have + ")",
                value -> {
                    withdrawExact(key, material, (int) Math.floor(value));
                    open();
                },
                this::open);
    }

    private void withdrawExact(String key, Material material, int amount) {
        int removed = gui.warehouseService().withdraw(owner, key, amount);
        deliver(key, material, removed);
    }

    private void withdraw(String key, Material material, boolean all) {
        int have = gui.warehouseService().getContents(owner).getOrDefault(key.toUpperCase(Locale.ROOT), 0);
        int want = all ? have : Math.min(material == null ? 64 : material.getMaxStackSize(), have);
        int removed = gui.warehouseService().withdraw(owner, key, want);
        deliver(key, material, removed);
        redraw();
    }

    private void deliver(String key, Material material, int removed) {
        if (removed > 0) {
            if (gui.gameDesignService() != null) {
                gui.gameDesignService().onWarehouseWithdrawn(owner);
            }
            int give = removed;
            while (give > 0) {
                int maxStack = material == null ? 64 : material.getMaxStackSize();
                int stack = Math.min(maxStack, give);
                ItemStack delivered = material == null
                        ? gui.dropTableService().customItem(key, stack)
                        : new ItemStack(material, stack);
                var leftover = viewer.getInventory().addItem(delivered);
                for (ItemStack overflow : leftover.values()) {
                    // Inventory full: return the overflow to the warehouse.
                    gui.warehouseService().deposit(owner, key, overflow.getAmount());
                }
                give -= stack;
            }
        }
    }

    private void expand() {
        var data = gui.dataStore().getOnline(viewer.getUniqueId());
        boolean ok = data != null && owner.equals(viewer.getUniqueId())
                && gui.warehouseService().expandCapacity(owner, data);
        viewer.sendMessage(Component.text(Lang.get(ok
                        ? "menu.warehouse.expand.done" : "menu.warehouse.expand.too-poor"),
                ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        redraw();
    }

    @Override
    public boolean clickPlayerInventory(InventoryClickEvent event) {
        return depositClicked(event, this);
    }

    /** Deposits the clicked player-inventory stack; shared by Warehouse and Deposit screens. */
    private boolean depositClicked(InventoryClickEvent event, Menu toRefresh) {
        if (event == null || !owner.equals(viewer.getUniqueId())) {
            return false;
        }
        ItemStack item = viewer.getInventory().getItem(event.getSlot());
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (isCustom(item)) {
            viewer.sendMessage(Component.text("Custom items stay in your inventory.",
                    NamedTextColor.GRAY));
            return true;
        }
        depositSlot(event.getSlot(), true);
        toRefresh.redraw();
        return true;
    }

    private boolean isCustom(ItemStack item) {
        return item.hasItemMeta()
                && !item.getItemMeta().getPersistentDataContainer().isEmpty();
    }

    /** @return amount stored, or -1 when the Warehouse rejected the stack (full). */
    private int depositSlot(int inventorySlot, boolean announce) {
        ItemStack item = viewer.getInventory().getItem(inventorySlot);
        if (item == null || item.getType().isAir() || isCustom(item)) {
            return 0;
        }
        int requested = item.getAmount();
        int stored = gui.warehouseService().deposit(owner, item.getType().name(), requested);
        if (stored <= 0) {
            if (announce) {
                viewer.sendMessage(Component.text(
                        "Warehouse is full. Withdraw items or expand capacity.",
                        NamedTextColor.RED));
            }
            return -1;
        }
        if (stored >= requested) {
            viewer.getInventory().setItem(inventorySlot, null);
        } else {
            item.setAmount(requested - stored);
        }
        if (announce) {
            viewer.sendMessage(Component.text("Deposited " + stored + " "
                    + Ui.pretty(item.getType().name()) + ".", NamedTextColor.GREEN));
        }
        return stored;
    }

    private void depositAll() {
        int total = 0;
        boolean full = false;
        // Main storage and hotbar only; never touch armor or offhand slots.
        for (int slot = 0; slot < 36; slot++) {
            int stored = depositSlot(slot, false);
            if (stored < 0) {
                full = true;
                break;
            }
            total += stored;
        }
        if (total > 0) {
            viewer.sendMessage(Component.text("Deposited " + total + " item(s)."
                    + (full ? " Warehouse is now full." : ""),
                    full ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        } else if (full) {
            viewer.sendMessage(Component.text(
                    "Warehouse is full. Withdraw items or expand capacity.",
                    NamedTextColor.RED));
        } else {
            viewer.sendMessage(Component.text("No eligible items to deposit.",
                    NamedTextColor.GRAY));
        }
    }

    private String prettify(String key) {
        String[] parts = key.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private String formatAmount(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.2f", amount);
    }

    private final class ItemActionsMenu extends Menu {
        private final String key;
        private final Material material;

        private ItemActionsMenu(String key, Material material) {
            super(WarehouseMenu.this.viewer);
            this.key = key;
            this.material = material;
        }

        @Override protected int rows() { return 3; }
        @Override protected Component title() {
            return Component.text(prettify(key), NamedTextColor.GOLD);
        }

        @Override
        protected void build() {
            int have = gui.warehouseService().getContents(owner)
                    .getOrDefault(key.toUpperCase(Locale.ROOT), 0);
            Material icon = material == null ? Material.PRISMARINE_SHARD : material;
            set(4, Icon.of(icon).name(prettify(key), NamedTextColor.WHITE)
                    .lore("Stored ×" + have, NamedTextColor.GOLD).build());
            set(10, Icon.of(Material.PAPER).name("Withdraw 1", NamedTextColor.GREEN).build(),
                    e -> finish(1));
            set(12, Icon.of(Material.CHEST).name("Withdraw 1 Stack", NamedTextColor.GREEN).build(),
                    e -> finish(Math.min(material == null ? 64 : material.getMaxStackSize(), have)));
            set(14, Icon.of(Material.NAME_TAG).name("Enter Exact Amount", NamedTextColor.AQUA).build(),
                    e -> gui.chatPrompt().requestNumber(viewer,
                            "How many " + prettify(key) + "? (stored " + have + ")",
                            value -> finish((int) Math.floor(value)), this::open));
            set(16, Icon.of(Material.HOPPER).name("Withdraw All", NamedTextColor.YELLOW)
                    .lore("Amount: " + have, NamedTextColor.GRAY).build(), e -> finish(have));
            backButton(22, "Warehouse", WarehouseMenu.this::open);
        }

        private void finish(int amount) {
            withdrawExact(key, material, Math.max(0, amount));
            WarehouseMenu.this.open();
        }
    }

    private final class DepositMenu extends Menu {
        private DepositMenu() {
            super(WarehouseMenu.this.viewer);
        }

        @Override protected int rows() { return 6; }
        @Override protected Component title() {
            return Component.text("Deposit to Warehouse", NamedTextColor.DARK_GREEN);
        }

        @Override
        protected void build() {
            int shown = 0;
            for (int inventorySlot = 0; inventorySlot < viewer.getInventory().getSize()
                    && shown < 45; inventorySlot++) {
                ItemStack item = viewer.getInventory().getItem(inventorySlot);
                if (item == null || item.getType().isAir() || isCustom(item)) continue;
                int sourceSlot = inventorySlot;
                set(shown++, Icon.of(item.getType()).amount(item.getAmount())
                        .name(Ui.pretty(item.getType().name()), NamedTextColor.WHITE)
                        .lore("Deposit this stack ×" + item.getAmount(), NamedTextColor.GREEN)
                        .build(), e -> {
                            depositSlot(sourceSlot, true);
                            redraw();
                        });
            }
            if (shown == 0) {
                set(22, Icon.of(Material.BARRIER).name("No eligible items",
                        NamedTextColor.GRAY).lore("Custom items stay in your inventory",
                        NamedTextColor.DARK_GRAY).build());
            }
            backButton(49, "Warehouse", WarehouseMenu.this::open);
            set(53, Icon.of(Material.HOPPER_MINECART)
                    .name("Deposit All", NamedTextColor.GREEN)
                    .lore("Deposit every eligible stack", NamedTextColor.GRAY).build(),
                    e -> {
                        depositAll();
                        redraw();
                    });
        }

        @Override
        public boolean clickPlayerInventory(InventoryClickEvent event) {
            return depositClicked(event, this);
        }
    }
}
