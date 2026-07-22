package dev.branzx.idle.schematic;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;

/**
 * The single definition of what a node's rotation means.
 *
 * <p>Rotation is stored as clockwise quarter-turns (0-3) viewed from above.
 * One step maps {@code (x, z) -> (-z, x)}: a block one step east ends up one
 * step south, which matches {@link StructureRotation#CLOCKWISE_90}.</p>
 *
 * <p>Blocks, spawn anchors, work anchors, and bounds all rotate through here.
 * Any conversion from definition-relative to world coordinates that skips this
 * class will silently disagree with the rest of the building.</p>
 */
public final class Rotation {

    private Rotation() {
    }

    public static int normalize(int quarterTurns) {
        return Math.floorMod(quarterTurns, 4);
    }

    public static int rotateX(int x, int z, int quarterTurns) {
        return switch (normalize(quarterTurns)) {
            case 1 -> -z;
            case 2 -> -x;
            case 3 -> z;
            default -> x;
        };
    }

    public static int rotateZ(int x, int z, int quarterTurns) {
        return switch (normalize(quarterTurns)) {
            case 1 -> x;
            case 2 -> -z;
            case 3 -> -x;
            default -> z;
        };
    }

    public static RelPos rotate(RelPos pos, int quarterTurns) {
        if (pos == null || normalize(quarterTurns) == 0) {
            return pos;
        }
        return new RelPos(rotateX(pos.x(), pos.z(), quarterTurns), pos.y(),
                rotateZ(pos.x(), pos.z(), quarterTurns));
    }

    public static StructureRotation structureRotation(int quarterTurns) {
        return switch (normalize(quarterTurns)) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };
    }

    /**
     * Rotates block state (stair/door/sign facing) without touching the
     * caller's instance: {@code BlockData#rotate} mutates in place, and the
     * definition's parsed data may be reused across nodes.
     */
    public static BlockData rotate(BlockData data, int quarterTurns) {
        if (normalize(quarterTurns) == 0) {
            return data;
        }
        BlockData copy = data.clone();
        copy.rotate(structureRotation(quarterTurns));
        return copy;
    }

    /** Rotated extent; min/max swap as the box turns. */
    public static SchematicDefinition.Bounds rotate(SchematicDefinition.Bounds bounds,
                                                    int quarterTurns) {
        if (normalize(quarterTurns) == 0) {
            return bounds;
        }
        int[] xs = {
                rotateX(bounds.minX(), bounds.minZ(), quarterTurns),
                rotateX(bounds.minX(), bounds.maxZ(), quarterTurns),
                rotateX(bounds.maxX(), bounds.minZ(), quarterTurns),
                rotateX(bounds.maxX(), bounds.maxZ(), quarterTurns)};
        int[] zs = {
                rotateZ(bounds.minX(), bounds.minZ(), quarterTurns),
                rotateZ(bounds.minX(), bounds.maxZ(), quarterTurns),
                rotateZ(bounds.maxX(), bounds.minZ(), quarterTurns),
                rotateZ(bounds.maxX(), bounds.maxZ(), quarterTurns)};
        int minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
        int maxX = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3]));
        int minZ = Math.min(Math.min(zs[0], zs[1]), Math.min(zs[2], zs[3]));
        int maxZ = Math.max(Math.max(zs[0], zs[1]), Math.max(zs[2], zs[3]));
        return new SchematicDefinition.Bounds(minX, bounds.minY(), minZ,
                maxX, bounds.maxY(), maxZ);
    }
}
