package dev.branzx.idlefarm.service.design;

import java.util.Locale;

/** Formatting helpers shared by the game-design services. */
public final class DesignText {

    private DesignText() {
    }

    public static String pretty(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return normalized.isEmpty() ? normalized
                : Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    public static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Escapes a value for embedding in a hand-built JSON detail string. */
    public static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String formatDuration(long millis) {
        long minutes = Math.max(1, millis / 60_000);
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : minutes + "m";
    }
}
