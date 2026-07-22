package dev.branzx.idle.schematic;

/**
 * A code-generated 2×1 Complex skin, so merging can be exercised before any
 * player-authored Complex exists.
 *
 * <p>Two pieces, one per chunk, forming a single 32 × 16 lodge. Each piece is
 * relative to its own chunk's centre column, so the west piece's {@code +7}
 * column and the east piece's {@code -8} column are neighbouring world blocks
 * — the pieces tile with no seam, which is exactly the property a real
 * Complex skin has to satisfy.</p>
 */
public final class DemoComplex {

    /** Skin id; the pieces are {@code <ID>_c0_0} and {@code <ID>_c1_0}. */
    public static final String ID = "demo_lodge";

    private DemoComplex() {
    }

    /** The western half: outer wall to the west, open to the east. */
    public static SchematicDefinition west() {
        SchematicDefinition definition = piece(ID + "_c0_0", true);
        // Workers live and work in the western half, so the anchors ride with
        // this piece. A Complex skin attaches anchors to the cell holding the
        // production node.
        definition.getSpawnAnchors().add(new RelPos(-5, 1, -4));
        definition.getSpawnAnchors().add(new RelPos(-5, 1, 0));
        definition.getSpawnAnchors().add(new RelPos(-5, 1, 4));
        definition.getWorkAnchors().add(new RelPos(0, 1, 0));
        definition.getWorkAnchors().add(new RelPos(2, 1, -3));
        definition.getWorkAnchors().add(new RelPos(2, 1, 3));
        definition.setWanderRadius(12);
        return definition;
    }

    /** The eastern half: outer wall to the east, open to the west. */
    public static SchematicDefinition east() {
        return piece(ID + "_c1_0", false);
    }

    /**
     * One half of the lodge.
     *
     * @param westHalf true for the western piece, which carries the west wall
     *                 and the door; the shared edge is left open on both
     *                 pieces so the interior runs through
     */
    private static SchematicDefinition piece(String id, boolean westHalf) {
        SchematicDefinition definition = new SchematicDefinition(id);
        for (int dx = -8; dx <= 7; dx++) {
            for (int dz = -8; dz <= 7; dz++) {
                // Foundation and floor across the whole chunk.
                definition.getBlocks().add(dx + ",-1," + dz + "|minecraft:cobblestone");
                definition.getBlocks().add(dx + ",0," + dz + "|minecraft:stone_bricks");

                boolean northSouthWall = dz == -8 || dz == 7;
                // Only the outer end of the lodge is walled; the shared edge
                // stays open or the two halves would be separate rooms.
                boolean endWall = westHalf ? dx == -8 : dx == 7;
                boolean corner = northSouthWall && endWall;
                // A doorway in the middle of the western end.
                boolean doorGap = westHalf && dx == -8 && (dz == 0 || dz == -1);

                for (int dy = 1; dy <= 4; dy++) {
                    String material;
                    if (doorGap && dy <= 2) {
                        material = "minecraft:air";
                    } else if (corner) {
                        material = "minecraft:oak_log";
                    } else if (northSouthWall || endWall) {
                        material = dy == 4 ? "minecraft:oak_planks" : "minecraft:spruce_planks";
                    } else {
                        material = "minecraft:air";
                    }
                    definition.getBlocks().add(dx + "," + dy + "," + dz + "|" + material);
                }
                // Flat roof over everything, so both halves close together.
                definition.getBlocks().add(dx + ",5," + dz + "|minecraft:spruce_slab");
            }
        }
        // Lanterns down the middle, offset so the two halves alternate.
        for (int dz = -6; dz <= 6; dz += 4) {
            definition.getBlocks().add((westHalf ? -3 : 3) + ",4," + dz
                    + "|minecraft:lantern[hanging=true]");
        }
        return definition;
    }
}
