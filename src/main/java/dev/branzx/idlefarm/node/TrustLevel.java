package dev.branzx.idlefarm.node;

public enum TrustLevel {
    VISITOR(1),
    HELPER(2),
    MANAGER(3);

    private final int rank;

    TrustLevel(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(TrustLevel other) {
        return this.rank >= other.rank;
    }

    public static TrustLevel fromString(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
