package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.node.NodeType;
import dev.branzx.idlefarm.service.design.DesignText;
import dev.branzx.idlefarm.service.design.RareResources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a just-collected resource breakdown into a short narrative, so a
 * collection reads like a crew returning from a shift rather than a counter
 * ticking up. Pure and Bukkit-free: it takes the moved-per-material map and
 * returns plain text lines the caller renders. No new data is invented — the
 * flavour is derived entirely from what was actually produced.
 */
public final class TripReport {

    private TripReport() {
    }

    /** How many of the highest-count commons to name before summarising. */
    private static final int NAMED_ITEMS = 3;

    /**
     * Builds the report lines for one node's collection. Returns an empty
     * list when nothing moved, so the caller can keep its plain message.
     *
     * @param type  the node family, choosing the crew verb
     * @param moved material name (any case) -> count actually collected
     */
    public static List<String> lines(NodeType type, Map<String, Integer> moved) {
        List<Map.Entry<String, Integer>> ranked = moved.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .toList();
        if (ranked.isEmpty()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        int total = ranked.stream().mapToInt(Map.Entry::getValue).sum();
        lines.add(crewVerb(type) + " " + total + " resource(s):");

        List<String> named = new ArrayList<>();
        for (int i = 0; i < Math.min(NAMED_ITEMS, ranked.size()); i++) {
            Map.Entry<String, Integer> entry = ranked.get(i);
            named.add(entry.getValue() + " " + DesignText.pretty(entry.getKey()));
        }
        int remainingKinds = ranked.size() - named.size();
        String body = String.join(", ", named);
        if (remainingKinds > 0) {
            body += " and " + remainingKinds + " more kind(s)";
        }
        lines.add("  " + body + ".");

        // Rare / capped finds are the exciting part of a shift; call them out
        // by name even when they are not among the top-count commons.
        List<String> finds = ranked.stream()
                .filter(entry -> RareResources.isCapped(entry.getKey().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue())
                        .reversed())
                .map(entry -> entry.getValue() + " " + DesignText.pretty(entry.getKey()))
                .toList();
        if (!finds.isEmpty()) {
            lines.add("  Notable finds: " + String.join(", ", finds) + "!");
        }
        return lines;
    }

    private static String crewVerb(NodeType type) {
        return switch (type) {
            case MINING -> "Your miners hauled back";
            case FARMING -> "Your farmhands harvested";
            case WOODCUTTING -> "Your lumberjacks felled";
            case LIVESTOCK -> "Your ranchers brought in";
            case HUNTER -> "Your hunters returned with";
            default -> "Your crew collected";
        };
    }
}
