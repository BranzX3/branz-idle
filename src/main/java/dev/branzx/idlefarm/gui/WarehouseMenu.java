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
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(gui.warehouseService().getContents(owner).entrySet());
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < entries.size(); i++) {
            Map.Entry<String, Integer> entry = entries.get(start + i);
            Material material = Material.matchMaterial(entry.getKey());
            if (material == null) {
                continue;
            }
            set(i, Icon.of(material)
                    .name(prettify(entry.getKey()), NamedTextColor.WHITE)
                    .amount(Math.min(64, entry.getValue()))
                    .lore(List.of("Stored: " + entry.getValue(),
                            "Click: withdraw 1 stack", "Shift-click: withdraw all"),
                            NamedTextColor.GRAY).build(),
                    e -> withdraw(entry.getKey(), material, e.isShiftClick()));
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

    private void withdraw(String key, Material material, boolean all) {
        int have = gui.warehouseService().getContents(owner).getOrDefault(key.toUpperCase(Locale.ROOT), 0);
        int want = all ? have : Math.min(material.getMaxStackSize(), have);
        int removed = gui.warehouseService().withdraw(owner, key, want);
        if (removed > 0) {
            int give = removed;
            while (give > 0) {
                int stack = Math.min(material.getMaxStackSize(), give);
                var leftover = viewer.getInventory().addItem(new ItemStack(material, stack));
                for (ItemStack overflow : leftover.values()) {
                    // Inventory full: return the overflow to the warehouse.
                    gui.warehouseService().deposit(owner, key, overflow.getAmount());
                }
                give -= stack;
            }
        }
        redraw();
    }

    private void expand() {
        var data = gui.dataStore().getOnline(viewer.getUniqueId());
        boolean ok = data != null && gui.warehouseService().expandCapacity(owner, amount -> {
            if (data.getBalance() < amount) {
                return false;
            }
            data.addBalance(-amount);
            return true;
        });
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
