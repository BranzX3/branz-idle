package dev.branzx.idlefarm.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Small builder for menu icons with clean (non-italic) names and lore. */
public final class Icon {

    private final ItemStack item;

    private Icon(Material material) {
        this.item = new ItemStack(material);
    }

    public static Icon of(Material material) {
        return new Icon(material);
    }

    public Icon name(String text, NamedTextColor color) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(text, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return this;
    }

    public Icon lore(List<String> lines, NamedTextColor color) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(Component.text(line, color).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return this;
    }

    public Icon lore(String line, NamedTextColor color) {
        return lore(List.of(line), color);
    }

    /** Pre-styled Component lore lines (from {@link Ui} helpers). */
    public Icon loreComponents(List<Component> lines) {
        ItemMeta meta = item.getItemMeta();
        meta.lore(lines);
        item.setItemMeta(meta);
        return this;
    }

    public Icon amount(int amount) {
        item.setAmount(Math.max(1, Math.min(64, amount)));
        return this;
    }

    public ItemStack build() {
        return item;
    }

    /** Gray glass filler used for empty frame slots. */
    public static ItemStack filler() {
        return Icon.of(Material.GRAY_STAINED_GLASS_PANE).name(" ", NamedTextColor.DARK_GRAY).build();
    }
}
