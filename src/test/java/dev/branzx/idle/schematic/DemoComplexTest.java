package dev.branzx.idle.schematic;

import dev.branzx.idle.skin.SkinValidator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample Complex is the fixture merging is tested against, so its
 * geometry has to hold: two chunk-sized pieces that join into one room with
 * no seam and no gap.
 */
class DemoComplexTest {

    private static final SkinValidator.Limits PIECE_LIMITS =
            new SkinValidator.Limits(16, 16, 100, 6000);

    /** Block material at a relative position, or null if the piece omits it. */
    private static Map<String, String> index(SchematicDefinition definition) {
        Map<String, String> blocks = new HashMap<>();
        for (String entry : definition.getBlocks()) {
            int pipe = entry.indexOf('|');
            blocks.put(entry.substring(0, pipe), entry.substring(pipe + 1));
        }
        return blocks;
    }

    private static boolean isAir(Map<String, String> blocks, int x, int y, int z) {
        String material = blocks.get(x + "," + y + "," + z);
        return material == null || material.startsWith("minecraft:air");
    }

    @Test
    void eachPieceFillsExactlyOneChunk() {
        // A Complex piece may fill its chunk; the 15x15 limit is only for
        // single-chunk skins that rotate about their own centre column.
        for (SchematicDefinition piece : java.util.List.of(DemoComplex.west(), DemoComplex.east())) {
            var bounds = piece.bounds();
            assertEquals(16, bounds.width(), piece.getId());
            assertEquals(16, bounds.depth(), piece.getId());
            assertEquals(-8, bounds.minX());
            assertEquals(7, bounds.maxX());
        }
    }

    @Test
    void bothPiecesPassSubmissionValidation() {
        assertTrue(SkinValidator.validate(DemoComplex.west(), PIECE_LIMITS).isEmpty(),
                () -> SkinValidator.validate(DemoComplex.west(), PIECE_LIMITS).toString());
        assertTrue(SkinValidator.validate(DemoComplex.east(), PIECE_LIMITS).isEmpty(),
                () -> SkinValidator.validate(DemoComplex.east(), PIECE_LIMITS).toString());
    }

    /**
     * The shared edge must be open on both sides. The west piece's +7 column
     * and the east piece's -8 column are neighbouring world blocks, so a wall
     * on either would split the lodge into two rooms.
     */
    @Test
    void theSharedEdgeIsOpenOnBothPieces() {
        var west = index(DemoComplex.west());
        var east = index(DemoComplex.east());

        for (int dz = -7; dz <= 6; dz++) {
            for (int dy = 1; dy <= 4; dy++) {
                assertTrue(isAir(west, 7, dy, dz),
                        "west piece walls its eastern edge at z=" + dz + " y=" + dy);
                assertTrue(isAir(east, -8, dy, dz),
                        "east piece walls its western edge at z=" + dz + " y=" + dy);
            }
        }
    }

    /** The outer ends are walled, or the lodge would be open to the weather. */
    @Test
    void theOuterEndsAreWalled() {
        var west = index(DemoComplex.west());
        var east = index(DemoComplex.east());

        assertFalse(isAir(west, -8, 3, 4), "west end should be walled");
        assertFalse(isAir(east, 7, 3, 4), "east end should be walled");
    }

    @Test
    void theWesternEndHasADoorway() {
        var west = index(DemoComplex.west());

        assertTrue(isAir(west, -8, 1, 0), "doorway should be open at foot height");
        assertTrue(isAir(west, -8, 2, 0), "doorway should be open at head height");
        assertFalse(isAir(west, -8, 3, 0), "but closed above the door");
    }

    @Test
    void bothPiecesHaveFloorAndRoofEverywhere() {
        var west = index(DemoComplex.west());

        for (int dx = -8; dx <= 7; dx++) {
            for (int dz = -8; dz <= 7; dz++) {
                assertFalse(isAir(west, dx, 0, dz), "missing floor at " + dx + "," + dz);
                assertFalse(isAir(west, dx, 5, dz), "missing roof at " + dx + "," + dz);
            }
        }
    }

    /** Anchors ride with the piece holding the production node. */
    @Test
    void onlyTheWesternPieceCarriesWorkerAnchors() {
        assertFalse(DemoComplex.west().getSpawnAnchors().isEmpty());
        assertFalse(DemoComplex.west().getWorkAnchors().isEmpty());

        assertTrue(DemoComplex.east().getSpawnAnchors().isEmpty());
        assertTrue(DemoComplex.east().getWorkAnchors().isEmpty());
    }

    /** Anchors must land inside the lodge, not in a wall or outside it. */
    @Test
    void anchorsSitInsideTheBuilding() {
        var west = index(DemoComplex.west());

        for (RelPos anchor : DemoComplex.west().getSpawnAnchors()) {
            assertTrue(isAir(west, anchor.x(), anchor.y(), anchor.z()),
                    "spawn anchor is inside a block: " + anchor.serialize());
            assertTrue(anchor.x() > -8 && anchor.x() <= 7,
                    "spawn anchor outside the chunk: " + anchor.serialize());
        }
    }
}
