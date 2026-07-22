package dev.branzx.idle.complex;

import java.util.List;
import java.util.Locale;

/**
 * A Complex footprint measured in chunks.
 *
 * <p>A Complex is one Production node plus enough adjacent Residential nodes
 * to fill the rectangle. Only the Production node produces, so a bigger shape
 * costs land and Coins without touching the economy — see
 * {@code docs/NODE_MERGE_AND_COMPLEX.md} §3.</p>
 */
public record ComplexShape(int width, int depth) {

    /** Shapes a player may form, smallest first. */
    public static final List<ComplexShape> ALLOWED = List.of(
            new ComplexShape(2, 1),
            new ComplexShape(1, 2),
            new ComplexShape(3, 1),
            new ComplexShape(1, 3),
            new ComplexShape(2, 2),
            new ComplexShape(3, 2),
            new ComplexShape(2, 3),
            new ComplexShape(3, 3));

    public ComplexShape {
        if (width < 1 || depth < 1) {
            throw new IllegalArgumentException("A shape must cover at least one chunk");
        }
    }

    /** Total chunks, of which exactly one is the Production node. */
    public int chunks() {
        return width * depth;
    }

    /** Residential plots the player must own to form this shape. */
    public int residentialNeeded() {
        return chunks() - 1;
    }

    public boolean isSquare() {
        return width == depth;
    }

    /**
     * Quarter-turns this shape may be rotated by.
     *
     * <p>A square Complex turns all four ways. A 3×1 turned 90° would need a
     * 1×3 arrangement of chunks, which the player does not own, so long
     * shapes are limited to a half turn.</p>
     */
    public List<Integer> allowedRotations() {
        return isSquare() ? List.of(0, 1, 2, 3) : List.of(0, 2);
    }

    /**
     * The cell a skin authored, for the chunk physically sitting at
     * {@code (col, row)} once the Complex is rotated.
     *
     * <p>Rotating a Complex turns each piece about its own chunk centre
     * <em>and</em> moves the pieces around each other — chunk centres are
     * themselves symmetric about the Complex centre. Turning the pieces
     * without swapping them would leave a 2×1 lodge with its door on the
     * wrong end and its open edge facing outwards.</p>
     *
     * <p>This is the inverse mapping: given where a chunk sits now, it
     * answers which authored piece belongs there.</p>
     */
    public int[] authoredCell(int col, int row, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> new int[]{row, width - 1 - col};
            case 2 -> new int[]{width - 1 - col, depth - 1 - row};
            case 3 -> new int[]{depth - 1 - row, col};
            default -> new int[]{col, row};
        };
    }

    /** Building area in blocks, centred on the shape (16 per chunk, less a seam). */
    public int blockWidth() {
        return width * 16 - 1;
    }

    public int blockDepth() {
        return depth * 16 - 1;
    }

    public String id() {
        return width + "x" + depth;
    }

    public static ComplexShape parse(String id) {
        if (id == null) {
            return null;
        }
        String[] parts = id.toLowerCase(Locale.ROOT).trim().split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new ComplexShape(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return id();
    }
}
