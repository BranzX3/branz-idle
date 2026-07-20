package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Paged warehouse view. Left-click withdraws one stack, shift-click
 * withdraws all of that material into the player's inventory.
 */
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

    @Override
    protected Component title() {
        return Component.text("Warehouse " + gui.warehouseService().total(owner) + "/"
                + gui.warehouseService().getCapacity(owner), NamedTextColor.GOLD);
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
                            Ui.line("Click: withdraw 1 stack", NamedTextColor.GRAY),
                            Ui.line("Shift-click: withdraw all", NamedTextColor.GRAY),
                            Ui.line("Middle click: type exact amount", NamedTextColor.AQUA))).build(),
                    e -> {
                        if (e.getClick() == org.bukkit.event.inventory.ClickType.MIDDLE) {
                            promptExact(entry.getKey(), material);
                        } else {
                            withdraw(entry.getKey(), material, e.isShiftClick());
                        }
                    });
        }

        for (int i = 45; i < 54; i++) {
            set(i, Icon.filler());
        }
        if (page > 0) {
            set(45, Icon.of(Material.ARROW).name("Previous", NamedTextColor.YELLOW).build(),
                    e -> new WarehouseMenu(viewer, gui, owner, page - 1).open());
        }
        set(49, Icon.of(Material.NETHER_STAR).name("Main Menu", NamedTextColor.GREEN).build(),
                e -> gui.openMainHub(viewer));
        double expandCost = gui.plugin().getConfig().getDouble("warehouse.expand-cost", 5000);
        int expandStep = gui.plugin().getConfig().getInt("warehouse.expand-step", 1000);
        set(48, Icon.of(Material.ENDER_CHEST).name("Expand +" + expandStep, NamedTextColor.AQUA)
                .lore("Cost: " + formatAmount(expandCost), NamedTextColor.GRAY).build(),
                e -> expand());
        if (start + PAGE_SIZE < entries.size()) {
            set(53, Icon.of(Material.ARROW).name("Next", NamedTextColor.YELLOW).build(),
                    e -> new WarehouseMenu(viewer, gui, owner, page + 1).open());
        }
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
        viewer.sendMessage(Component.text(ok ? "Warehouse expanded!" : "Not enough money to expand.",
                ok ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (ok) {
            redraw();
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
}
