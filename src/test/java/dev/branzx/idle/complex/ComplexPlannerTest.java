package dev.branzx.idle.complex;

import dev.branzx.idle.node.ChunkKey;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Complex detection is pure grid geometry. A mistake here would hand a player
 * a shape their land cannot support, or silently omit one it can.
 */
class ComplexPlannerTest {

    private static final ChunkKey ANCHOR = new ChunkKey("world", 10, 10);

    /** Residential plots the player owns, by chunk coordinate. */
    private static Predicate<ChunkKey> owning(String... coords) {
        Set<String> owned = Set.of(coords);
        return chunk -> owned.contains(chunk.x() + "," + chunk.z());
    }

    private static boolean offers(java.util.List<ComplexPlanner.Placement> options, String shapeId) {
        return options.stream().anyMatch(p -> p.shape().id().equals(shapeId));
    }

    @Test
    void barrenLandOffersNothing() {
        assertTrue(ComplexPlanner.options(ANCHOR, chunk -> false).isEmpty());
    }

    @Test
    void oneNeighbourOffersTheTwoChunkShapes() {
        var options = ComplexPlanner.options(ANCHOR, owning("11,10"));

        assertTrue(offers(options, "2x1"));
        assertFalse(offers(options, "3x1"), "a third chunk is not owned");
        assertFalse(offers(options, "2x2"));
    }

    /** The anchor may sit at either end of a 2×1, so both offsets are valid. */
    @Test
    void theAnchorMaySitInAnyCellOfTheShape() {
        var west = ComplexPlanner.placementsFor(new ComplexShape(2, 1), ANCHOR, owning("9,10"));
        var east = ComplexPlanner.placementsFor(new ComplexShape(2, 1), ANCHOR, owning("11,10"));

        assertEquals(1, west.size());
        assertEquals(1, east.size());
        assertEquals(1, west.getFirst().anchorCol(), "anchor is the eastern cell");
        assertEquals(0, east.getFirst().anchorCol(), "anchor is the western cell");
    }

    @Test
    void aFullThreeByThreeIsOfferedWhenAllEightNeighboursAreOwned() {
        var options = ComplexPlanner.options(ANCHOR,
                owning("9,9", "10,9", "11,9", "9,10", "11,10", "9,11", "10,11", "11,11"));

        assertTrue(offers(options, "3x3"));
        assertTrue(offers(options, "2x2"), "smaller shapes still fit inside");
    }

    /** Largest first, so a player who can build big is offered big. */
    @Test
    void optionsAreOrderedLargestFirst() {
        var options = ComplexPlanner.options(ANCHOR,
                owning("9,9", "10,9", "11,9", "9,10", "11,10", "9,11", "10,11", "11,11"));

        assertEquals(9, options.getFirst().shape().chunks());
    }

    @Test
    void aGapBreaksTheRectangle() {
        // An L of neighbours cannot form a 2×2, which needs all three.
        var options = ComplexPlanner.options(ANCHOR, owning("11,10", "10,11"));

        assertFalse(offers(options, "2x2"));
        assertTrue(offers(options, "2x1"));
    }

    @Test
    void coverListsEveryChunkOfTheRectangle() {
        var chunks = ComplexPlanner.cover(new ComplexShape(3, 2), new ChunkKey("world", 5, 7));

        assertEquals(6, chunks.size());
        assertTrue(chunks.contains(new ChunkKey("world", 5, 7)));
        assertTrue(chunks.contains(new ChunkKey("world", 7, 8)));
        assertFalse(chunks.contains(new ChunkKey("world", 8, 7)), "one column too far east");
    }

    @Test
    void supportChunksExcludeTheProductionNode() {
        var placement = ComplexPlanner.firstFor(new ComplexShape(2, 1), ANCHOR, owning("11,10"));

        assertNotNull(placement);
        assertEquals(1, placement.supportChunks().size());
        assertFalse(placement.supportChunks().contains(ANCHOR));
    }

    @Test
    void firstForReturnsNullWhenTheLandDoesNotSupportTheShape() {
        assertNull(ComplexPlanner.firstFor(new ComplexShape(3, 3), ANCHOR, owning("11,10")));
    }

    /** Placements never cross worlds, however the coordinates line up. */
    @Test
    void detectionStaysWithinTheAnchorsWorld() {
        var placement = ComplexPlanner.firstFor(new ComplexShape(2, 1), ANCHOR, owning("11,10"));

        assertNotNull(placement);
        placement.chunks().forEach(chunk -> assertEquals("world", chunk.world()));
    }
}
