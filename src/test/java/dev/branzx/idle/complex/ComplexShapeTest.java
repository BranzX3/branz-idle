package dev.branzx.idle.complex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexShapeTest {

    @Test
    void aComplexSpendsOneChunkOnProductionAndTheRestOnResidential() {
        assertEquals(9, new ComplexShape(3, 3).chunks());
        assertEquals(8, new ComplexShape(3, 3).residentialNeeded());
        assertEquals(1, new ComplexShape(2, 1).residentialNeeded());
    }

    /**
     * A long Complex cannot turn sideways: the rotated footprint would need
     * chunks arranged the other way, which the player does not own.
     */
    @Test
    void onlySquareShapesRotateAllFourWays() {
        assertEquals(4, new ComplexShape(3, 3).allowedRotations().size());
        assertEquals(4, new ComplexShape(2, 2).allowedRotations().size());

        assertEquals(java.util.List.of(0, 2), new ComplexShape(3, 1).allowedRotations());
        assertEquals(java.util.List.of(0, 2), new ComplexShape(3, 2).allowedRotations());
    }

    @Test
    void buildingAreaLeavesNoSeamBetweenChunks() {
        // 16 blocks per chunk, less one so the footprint stays odd and centred.
        assertEquals(15, new ComplexShape(1, 1).blockWidth());
        assertEquals(31, new ComplexShape(2, 1).blockWidth());
        assertEquals(47, new ComplexShape(3, 3).blockWidth());
        assertEquals(31, new ComplexShape(3, 2).blockDepth());
    }

    @Test
    void squareDetection() {
        assertTrue(new ComplexShape(2, 2).isSquare());
        assertFalse(new ComplexShape(3, 2).isSquare());
    }

    @Test
    void idsRoundTrip() {
        assertEquals("3x2", new ComplexShape(3, 2).id());
        assertEquals(new ComplexShape(3, 2), ComplexShape.parse("3x2"));
        assertEquals(new ComplexShape(3, 2), ComplexShape.parse(" 3X2 "));
    }

    @Test
    void malformedIdsResolveToNullRatherThanThrowing() {
        assertNull(ComplexShape.parse(null));
        assertNull(ComplexShape.parse("3"));
        assertNull(ComplexShape.parse("axb"));
        assertNull(ComplexShape.parse("0x3"), "a shape must cover a chunk");
        assertNull(ComplexShape.parse("4x4"), "unsupported shapes must not reach the planner");
        assertNull(ComplexShape.parse("10000x10000"), "unbounded input would exhaust the server");
    }

    @Test
    void aShapeMustCoverAtLeastOneChunk() {
        assertThrows(IllegalArgumentException.class, () -> new ComplexShape(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new ComplexShape(2, -1));
    }

    // ---- rotation cell mapping ----

    private static int[] cell(ComplexShape shape, int col, int row, int turns) {
        return shape.authoredCell(col, row, turns);
    }

    @Test
    void anUnrotatedComplexRendersEachCellWhereItWasAuthored() {
        var shape = new ComplexShape(3, 3);

        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{2, 1}, cell(shape, 2, 1, 0));
    }

    /**
     * The halves of a 2×1 must swap on a half turn. Turning each piece in
     * place without swapping them would leave the door on the wrong end and
     * the open seam facing outwards.
     */
    @Test
    void aHalfTurnSwapsTheEndsOfALongComplex() {
        var lodge = new ComplexShape(2, 1);

        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{1, 0}, cell(lodge, 0, 0, 2));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{0, 0}, cell(lodge, 1, 0, 2));
    }

    @Test
    void aHalfTurnMirrorsBothAxes() {
        var shape = new ComplexShape(3, 2);

        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{2, 1}, cell(shape, 0, 0, 2));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{0, 0}, cell(shape, 2, 1, 2));
    }

    /** Four turns must land every cell back where it started. */
    @Test
    void fourQuarterTurnsRestoreEveryCell() {
        var shape = new ComplexShape(3, 3);
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                int[] once = cell(shape, col, row, 1);
                int[] twice = cell(shape, once[0], once[1], 1);
                int[] thrice = cell(shape, twice[0], twice[1], 1);
                int[] full = cell(shape, thrice[0], thrice[1], 1);

                org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{col, row}, full,
                        "cell " + col + "," + row + " did not return home");
            }
        }
    }

    /** Every cell maps to a distinct cell, so no piece is dropped or doubled. */
    @Test
    void rotationIsAPermutationOfTheCells() {
        var shape = new ComplexShape(3, 3);
        for (int turns = 0; turns < 4; turns++) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int col = 0; col < 3; col++) {
                for (int row = 0; row < 3; row++) {
                    int[] mapped = cell(shape, col, row, turns);
                    assertTrue(mapped[0] >= 0 && mapped[0] < 3, "col out of range");
                    assertTrue(mapped[1] >= 0 && mapped[1] < 3, "row out of range");
                    assertTrue(seen.add(mapped[0] + "," + mapped[1]),
                            "two chunks resolved to the same piece at turn " + turns);
                }
            }
        }
    }

    @Test
    void theCentreOfAnOddSquareStaysPut() {
        var shape = new ComplexShape(3, 3);

        for (int turns = 0; turns < 4; turns++) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(new int[]{1, 1},
                    cell(shape, 1, 1, turns));
        }
    }

    /** Both orientations of each rectangle are offerable. */
    @Test
    void allowedShapesIncludeBothOrientations() {
        assertTrue(ComplexShape.ALLOWED.contains(new ComplexShape(3, 1)));
        assertTrue(ComplexShape.ALLOWED.contains(new ComplexShape(1, 3)));
        assertTrue(ComplexShape.ALLOWED.contains(new ComplexShape(3, 2)));
        assertTrue(ComplexShape.ALLOWED.contains(new ComplexShape(2, 3)));
    }
}
