package dev.branzx.idle.gui;

import dev.branzx.idle.IdlePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/** Permanent, server-owned hotbar button for opening Idle. */
public final class MenuItemService implements Listener {

    private final IdlePlugin plugin;
    private final GuiManager gui;
    private final NamespacedKey key;
    private final boolean enabled;
    private final int slot;
    private final Material material;

    public MenuItemService(IdlePlugin plugin, GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
        this.key = new NamespacedKey(plugin, "menu_item");
        this.enabled = plugin.getConfig().getBoolean("menu-item.enabled", true);
        this.slot = Math.max(0, Math.min(8, plugin.getConfig().getInt("menu-item.slot", 8)));
        Material configured = Material.matchMaterial(
                plugin.getConfig().getString("menu-item.material", "COMPASS"));
        this.material = configured == null || !configured.isItem() ? Material.COMPASS : configured;
    }

    public void start() {
        if (!enabled) return;
        long interval = Math.max(20L,
                plugin.getConfig().getLong("menu-item.repair-interval-ticks", 100L));
        plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> plugin.getServer().getOnlinePlayers().forEach(this::ensure),
                1L, interval);
    }

    public void ensure(Player player) {
        if (!enabled || !player.isOnline()) return;
        removeDuplicates(player);
        ItemStack current = player.getInventory().getItem(slot);
        if (isMenuItem(current)) return;
        if (current != null && !current.getType().isAir()) {
            // Reserve the slot before reinserting the displaced stack so
            // Inventory#addItem cannot choose the same slot and get overwritten.
            player.getInventory().setItem(slot, create());
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(current);
            overflow.values().forEach(item -> safeDrop(player, item));
            return;
        }
        player.getInventory().setItem(slot, create());
    }

    public boolean isMenuItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(key, PersistentDataType.BYTE);
    }

    private ItemStack create() {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("เมนู Idle • กดใช้", NamedTextColor.GOLD));
        meta.lore(java.util.List.of(
                Component.text("ช่องเมนูถาวร", NamedTextColor.GRAY),
                Component.text("เปิด UI ที่เหมาะกับอุปกรณ์ของคุณ", NamedTextColor.AQUA)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private void removeDuplicates(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            if (i != slot && isMenuItem(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, null);
            }
        }
        if (isMenuItem(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private void safeDrop(Player player, ItemStack item) {
        Item dropped = player.getWorld().dropItem(player.getLocation(), item);
        dropped.setOwner(player.getUniqueId());
        dropped.setPickupDelay(0);
    }

    private void repairNextTick(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> ensure(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (!enabled || event.getHand() != EquipmentSlot.HAND
                || !isMenuItem(event.getItem())) return;
        event.setCancelled(true);
        gui.openMainHub(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!enabled || !(event.getWhoClicked() instanceof Player player)) return;
        boolean reservedSlot = event.getClickedInventory() == player.getInventory()
                && event.getSlot() == slot;
        boolean numberSwap = event.getHotbarButton() == slot;
        if (reservedSlot || numberSwap || isMenuItem(event.getCurrentItem())
                || isMenuItem(event.getCursor())) {
            event.setCancelled(true);
            repairNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!enabled || !(event.getWhoClicked() instanceof Player player)) return;
        int top = event.getView().getTopInventory().getSize();
        boolean touchesReserved = event.getRawSlots().stream()
                .anyMatch(raw -> raw >= top && event.getView().convertSlot(raw) == slot);
        if (touchesReserved || event.getNewItems().values().stream().anyMatch(this::isMenuItem)) {
            event.setCancelled(true);
            repairNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isMenuItem(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        repairNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isMenuItem(event.getMainHandItem()) || isMenuItem(event.getOffHandItem())) {
            event.setCancelled(true);
            repairNextTick(event.getPlayer());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isMenuItem);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { repairNextTick(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { repairNextTick(event.getPlayer()); }
    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) repairNextTick(player);
    }
}
