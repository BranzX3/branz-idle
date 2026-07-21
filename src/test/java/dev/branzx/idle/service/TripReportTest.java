package dev.branzx.idle.service;

import dev.branzx.idle.node.NodeType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripReportTest {

    @Test
    void emptyBreakdownProducesNoLines() {
        assertTrue(TripReport.lines(NodeType.MINING, Map.of()).isEmpty());
        assertTrue(TripReport.lines(NodeType.MINING, Map.of("COBBLESTONE", 0)).isEmpty());
    }

    @Test
    void namesTopThreeCommonsAndSummarisesTheRest() {
        Map<String, Integer> moved = new LinkedHashMap<>();
        moved.put("COBBLESTONE", 214);
        moved.put("COAL", 40);
        moved.put("RAW_IRON", 12);
        moved.put("ANDESITE", 5);

        List<String> lines = TripReport.lines(NodeType.MINING, moved);

        assertEquals("Your miners hauled back 271 resource(s):", lines.get(0));
        // Highest counts first, fourth kind rolled into the summary.
        assertEquals("  214 Cobblestone, 40 Coal, 12 Raw iron and 1 more kind(s).",
                lines.get(1));
        assertEquals(2, lines.size());
    }

    @Test
    void callsOutCappedFindsEvenWhenNotTopCount() {
        Map<String, Integer> moved = new LinkedHashMap<>();
        moved.put("COBBLESTONE", 500);
        moved.put("COAL", 120);
        moved.put("RAW_IRON", 60);
        moved.put("DIAMOND", 2);

        List<String> lines = TripReport.lines(NodeType.MINING, moved);

        assertEquals(3, lines.size());
        assertEquals("  Notable finds: 2 Diamond!", lines.get(2));
    }

    @Test
    void usesTheFamilyVerbPerNodeType() {
        assertEquals("Your lumberjacks felled 3 resource(s):",
                TripReport.lines(NodeType.WOODCUTTING, Map.of("OAK_LOG", 3)).get(0));
        assertEquals("Your ranchers brought in 3 resource(s):",
                TripReport.lines(NodeType.LIVESTOCK, Map.of("BEEF", 3)).get(0));
    }
}
