package dev.branzx.idlefarm.worker;

import net.kyori.adventure.text.format.NamedTextColor;

public enum Rarity {
    COMMON(NamedTextColor.WHITE, 20),
    UNCOMMON(NamedTextColor.GREEN, 40),
    RARE(NamedTextColor.AQUA, 60),
    EPIC(NamedTextColor.LIGHT_PURPLE, 80),
    LEGENDARY(NamedTextColor.GOLD, 100);

    private final NamedTextColor color;
    private final int levelCap;

    Rarity(NamedTextColor color, int levelCap) {
        this.color = color;
        this.levelCap = levelCap;
    }

    public NamedTextColor color() {
        return color;
    }

    public int levelCap() {
        return levelCap;
    }

    public Rarity next() {
        int ordinal = ordinal();
        return ordinal + 1 < values().length ? values()[ordinal + 1] : null;
    }

    public static Rarity fromString(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
