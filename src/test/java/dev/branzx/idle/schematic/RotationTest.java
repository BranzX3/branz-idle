package dev.branzx.idle.schematic;

import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Rotation is applied to blocks, anchors, and bounds by separate call sites.
 * If the transform is not self-consistent, a rotated building and its workers
 * end up in different places.
 */
class RotationTest {

    private static int[] turn(int x, int z, int quarterTurns) {
        return new int[]{Rotation.rotateX(x, z, quarterTurns), Rotation.rotateZ(x, z, quarterTurns)};
    }

    @Test
    void oneQuarterTurnSendsEastToSouth() {
        // +X is east and +Z is south, so a clockwise turn from above maps
        // east onto south. This is the convention CLOCKWISE_90 uses.
        assertEquals(0, turn(1, 0, 1)[0]);
        assertEquals(1, turn(1, 0, 1)[1]);
    }

    @Test
    void fourQuarterTurnsReturnToStart() {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                assertEquals(x, turn(x, z, 4)[0]);
                assertEquals(z, turn(x, z, 4)[1]);
            }
        }
    }

    @Test
    void halfTurnNegatesBothAxes() {
        assertEquals(-2, turn(2, 5, 2)[0]);
        assertEquals(-5, turn(2, 5, 2)[1]);
    }

    @Test
    void oppositeTurnsCancel() {
        int[] there = turn(3, -1, 1);
        int[] back = turn(there[0], there[1], 3);

        assertEquals(3, back[0]);
        assertEquals(-1, back[1]);
    }

    @Test
    void negativeAndOversizedTurnCountsWrap() {
        assertEquals(0, Rotation.normalize(4));
        assertEquals(3, Rotation.normalize(-1));
        assertEquals(1, Rotation.normalize(9));

        // A caller accumulating turns must land on the same result as a
        // caller passing the normalized count.
        assertEquals(turn(2, 1, 1)[0], turn(2, 1, 5)[0]);
        assertEquals(turn(2, 1, 1)[1], turn(2, 1, 5)[1]);
    }

    @Test
    void relativePositionsRotateWithTheBlocks() {
        RelPos rotated = Rotation.rotate(new RelPos(1, 2, 0), 1);

        assertEquals(0, rotated.x());
        assertEquals(2, rotated.y(), "height must not change");
        assertEquals(1, rotated.z());
    }

    @Test
    void nullAnchorSurvivesRotation() {
        // Unset per-slot anchors are stored as null and must stay null.
        org.junit.jupiter.api.Assertions.assertNull(Rotation.rotate((RelPos) null, 2));
    }

    @Test
    void structureRotationMatchesQuarterTurns() {
        assertEquals(StructureRotation.NONE, Rotation.structureRotation(0));
        assertEquals(StructureRotation.CLOCKWISE_90, Rotation.structureRotation(1));
        assertEquals(StructureRotation.CLOCKWISE_180, Rotation.structureRotation(2));
        assertEquals(StructureRotation.COUNTERCLOCKWISE_90, Rotation.structureRotation(3));
    }

    @Test
    void boundsSwapWidthAndDepthOnAQuarterTurn() {
        // A 3-wide, 7-deep footprint becomes 7-wide and 3-deep.
        SchematicDefinition.Bounds bounds = new SchematicDefinition.Bounds(-1, 0, -3, 1, 4, 3);
        SchematicDefinition.Bounds rotated = Rotation.rotate(bounds, 1);

        assertEquals(7, rotated.width());
        assertEquals(3, rotated.depth());
        assertEquals(bounds.height(), rotated.height());
        assertEquals(bounds.minY(), rotated.minY());
    }

    @Test
    void asymmetricBoundsShiftButKeepTheirSize() {
        // Offset box: rotation moves it, and the ground sampling that reads
        // these bounds must follow it rather than the original footprint.
        SchematicDefinition.Bounds bounds = new SchematicDefinition.Bounds(2, 0, 0, 4, 1, 1);
        SchematicDefinition.Bounds rotated = Rotation.rotate(bounds, 1);

        assertEquals(bounds.depth(), rotated.width());
        assertEquals(bounds.width(), rotated.depth());
        assertEquals(-1, rotated.minX());
        assertEquals(2, rotated.minZ());
    }
}
