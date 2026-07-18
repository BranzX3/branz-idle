package dev.branzx.idlefarm.node;

public enum NodeType {
    RESIDENTIAL(false),
    MINING(true),
    FARMING(true),
    WOODCUTTING(true),
    LIVESTOCK(true),
    HUNTER(true);

    private final boolean production;

    NodeType(boolean production) {
        this.production = production;
    }

    public boolean isProduction() {
        return production;
    }

    public static NodeType fromString(String value) {
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
