package dev.branzx.idlefarm.worker;

/**
 * Growth-bias personality rolled at mint. Level-up stat points land randomly
 * but weighted toward the trait's favored stat.
 */
public enum Trait {
    HARDWORKING("Diligence"),
    FORTUNATE("Luck"),
    TIRELESS("Stamina"),
    SWIFT("Speed"),
    BALANCED(null);

    private final String favoredStat;

    Trait(String favoredStat) {
        this.favoredStat = favoredStat;
    }

    /** Null for BALANCED (uniform allocation). */
    public String favoredStat() {
        return favoredStat;
    }

    public static Trait fromString(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
