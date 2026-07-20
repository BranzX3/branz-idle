package dev.branzx.idlefarm.service.design;

import java.util.Locale;
import java.util.Set;

/**
 * Account-wide capped rare resources. Both passive production and event loot
 * must consult the same lists, otherwise one path can bypass the cap.
 */
public final class RareResources {

    public static final Set<String> DAILY =
            Set.of("DIAMOND", "EMERALD", "NAUTILUS_SHELL", "GHAST_TEAR");
    public static final Set<String> WEEKLY =
            Set.of("ANCIENT_DEBRIS", "NETHERITE_SCRAP", "WITHER_SKELETON_SKULL");

    private RareResources() {
    }

    public static boolean isCapped(String material) {
        String id = material.toUpperCase(Locale.ROOT);
        return DAILY.contains(id) || WEEKLY.contains(id);
    }
}
