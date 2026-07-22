package dev.branzx.idle.complex;

import dev.branzx.idle.node.ChunkKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Works out which Complex shapes a Production node could form from the land
 * around it.
 *
 * <p>Pure geometry over the chunk grid: the caller supplies a test for
 * "this chunk is a Residential plot of mine that is free to join", so the
 * rules stay unit-testable and the world lookup lives in
 * {@link ComplexService}.</p>
 */
public final class ComplexPlanner {

    /**
     * One way a shape can sit over the grid while covering the anchor.
     *
     * @param anchorCol column the Production node occupies, 0-based from the
     *                  west edge of the rectangle
     * @param anchorRow row the Production node occupies, 0-based from the
     *                  north edge
     */
    public record Placement(ComplexShape shape, ChunkKey min, ChunkKey anchor,
                            int anchorCol, int anchorRow, List<ChunkKey> chunks) {

        /** The Residential chunks — every covered chunk except the anchor. */
        public List<ChunkKey> supportChunks() {
            return chunks.stream().filter(chunk -> !chunk.equals(anchor)).toList();
        }

        public String describe() {
            return shape.id() + " at " + min.x() + "," + min.z();
        }
    }

    private ComplexPlanner() {
    }

    /** Every chunk a shape covers when its north-west corner sits at {@code min}. */
    public static List<ChunkKey> cover(ComplexShape shape, ChunkKey min) {
        List<ChunkKey> chunks = new ArrayList<>(shape.chunks());
        for (int row = 0; row < shape.depth(); row++) {
            for (int col = 0; col < shape.width(); col++) {
                chunks.add(new ChunkKey(min.world(), min.x() + col, min.z() + row));
            }
        }
        return chunks;
    }

    /**
     * Every placement of every allowed shape that covers {@code anchor} and
     * whose other chunks all pass {@code available}.
     *
     * <p>Ordered largest first, because a player who can form a 3×3 is
     * offered it before the 2×1 that also fits inside it.</p>
     */
    public static List<Placement> options(ChunkKey anchor, Predicate<ChunkKey> available) {
        List<Placement> options = new ArrayList<>();
        for (ComplexShape shape : ComplexShape.ALLOWED) {
            options.addAll(placementsFor(shape, anchor, available));
        }
        options.sort((a, b) -> Integer.compare(b.shape().chunks(), a.shape().chunks()));
        return options;
    }

    /** Placements of one shape; the anchor may sit in any of its cells. */
    public static List<Placement> placementsFor(ComplexShape shape, ChunkKey anchor,
                                                Predicate<ChunkKey> available) {
        List<Placement> found = new ArrayList<>();
        for (int row = 0; row < shape.depth(); row++) {
            for (int col = 0; col < shape.width(); col++) {
                ChunkKey min = new ChunkKey(anchor.world(), anchor.x() - col, anchor.z() - row);
                List<ChunkKey> chunks = cover(shape, min);
                boolean usable = true;
                for (ChunkKey chunk : chunks) {
                    if (chunk.equals(anchor)) {
                        continue; // the Production node itself
                    }
                    if (!available.test(chunk)) {
                        usable = false;
                        break;
                    }
                }
                if (usable) {
                    found.add(new Placement(shape, min, anchor, col, row, chunks));
                }
            }
        }
        return found;
    }

    /**
     * The best placement of a given shape, or null when the land does not
     * support it. "Best" is simply the first found; every placement of a
     * shape is equivalent in area, and the player picks the shape not the
     * offset.
     */
    public static Placement firstFor(ComplexShape shape, ChunkKey anchor,
                                     Predicate<ChunkKey> available) {
        List<Placement> placements = placementsFor(shape, anchor, available);
        return placements.isEmpty() ? null : placements.getFirst();
    }
}
