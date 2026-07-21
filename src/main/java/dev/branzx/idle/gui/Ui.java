package dev.branzx.idle.gui;

import dev.branzx.idle.worker.Rarity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Shared, compact visual language for every player-facing menu. */
public final class Ui {

    private Ui() {
    }

    public static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public static Component divider() {
        return line("----------------", NamedTextColor.DARK_GRAY);
    }

    public static Component click(String action) {
        return line("Click: " + action, NamedTextColor.YELLOW);
    }

    public static Component status(String label, NamedTextColor color) {
        return line("[ " + label + " ]", color);
    }

    public static Component bar(String label, double fraction, NamedTextColor fillColor,
                                String suffix) {
        int width = 10;
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        Component barPart = Component.text("|".repeat(filled), fillColor)
                .append(Component.text(".".repeat(width - filled), NamedTextColor.DARK_GRAY));
        return Component.text(label + " ", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(barPart)
                .append(Component.text(" " + suffix, NamedTextColor.GRAY));
    }

    public static String num(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000) {
            return trim(value / 1_000_000.0) + "M";
        }
        if (abs >= 10_000) {
            return trim(value / 1_000.0) + "k";
        }
        return trim(value);
    }

    private static String trim(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }

    public static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    public static Component stars(Rarity rarity) {
        int tier = rarity.ordinal() + 1;
        return Component.text("*".repeat(tier), rarity.color())
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("-".repeat(Rarity.values().length - tier),
                        NamedTextColor.DARK_GRAY));
    }

    public static Component stat(String symbol, String name, int value, int softMax,
                                 NamedTextColor color) {
        int width = 5;
        int filled = Math.max(0, Math.min(width,
                (int) Math.ceil(value / (double) softMax * width)));
        return Component.text(" " + symbol + " ", color)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(String.format("%-9s", name), NamedTextColor.GRAY))
                .append(Component.text(String.format("%3d ", value), NamedTextColor.WHITE))
                .append(Component.text("|".repeat(filled), color))
                .append(Component.text(".".repeat(width - filled), NamedTextColor.DARK_GRAY));
    }

    public static String pretty(String key) {
        String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) {
                result.append(' ');
            }
            if (!part.isEmpty()) {
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return result.toString();
    }
}
