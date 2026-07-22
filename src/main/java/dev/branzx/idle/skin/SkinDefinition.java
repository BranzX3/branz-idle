package dev.branzx.idle.skin;

import dev.branzx.idle.node.NodeType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A selectable building appearance: a family of schematic definitions, one
 * per tier, plus the metadata that credits its author.
 *
 * <p>A skin is a <em>choice between</em> blueprints; a
 * {@link dev.branzx.idle.schematic.SchematicDefinition} is one blueprint. A
 * node wears exactly one skin, and that skin supplies the blueprint at
 * whatever tier the node has reached.</p>
 */
public final class SkinDefinition {

    private final String id;
    private String display;
    /** Author uuid; null for skins shipped by the server. */
    private UUID author;
    private String authorName;
    /** Empty means the skin suits every production type. */
    private final Set<NodeType> nodeTypes = new LinkedHashSet<>();
    /** tier -> schematic definition id. Tier 1 is the only required entry. */
    private final Map<Integer, String> tiers = new LinkedHashMap<>();
    /** Free-text unlock tag, e.g. "default" or "contest_s1". */
    private String unlock = "default";
    /** Optional exclusive variant granted to the author only. */
    private String winnerVariant;
    /** Set for a Complex skin, e.g. "3x2"; null for a single-chunk skin. */
    private String shape;
    /** "col,row" -> schematic id, for a Complex skin's per-chunk pieces. */
    private final Map<String, String> pieces = new LinkedHashMap<>();

    public SkinDefinition(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getDisplay() {
        return display == null || display.isBlank() ? id : display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public UUID getAuthor() {
        return author;
    }

    public void setAuthor(UUID author) {
        this.author = author;
    }

    /** Shown wherever the skin is listed; author credit is the contest prize. */
    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public Set<NodeType> getNodeTypes() {
        return nodeTypes;
    }

    public Map<Integer, String> getTiers() {
        return tiers;
    }

    public String getUnlock() {
        return unlock;
    }

    public void setUnlock(String unlock) {
        this.unlock = unlock == null || unlock.isBlank() ? "default" : unlock;
    }

    public String getWinnerVariant() {
        return winnerVariant;
    }

    public void setWinnerVariant(String winnerVariant) {
        this.winnerVariant = winnerVariant == null || winnerVariant.isBlank()
                ? null : winnerVariant;
    }

    public String getShape() {
        return shape;
    }

    public void setShape(String shape) {
        this.shape = shape == null || shape.isBlank() ? null : shape;
    }

    public Map<String, String> getPieces() {
        return pieces;
    }

    /** True for a skin authored to span a Complex rather than one chunk. */
    public boolean isComplexSkin() {
        return shape != null;
    }

    /**
     * Schematic id for one cell of a Complex skin.
     *
     * <p>Returns null for a cell the skin does not define, which the caller
     * treats as "leave that chunk empty" — a courtyard or plaza is a legal
     * and desirable part of a large layout.</p>
     */
    public String pieceIdFor(int col, int row) {
        return pieces.get(col + "," + row);
    }

    /** A skin every player may wear without being granted it individually. */
    public boolean isDefaultUnlock() {
        return "default".equalsIgnoreCase(unlock);
    }

    public boolean appliesTo(NodeType type) {
        return nodeTypes.isEmpty() || nodeTypes.contains(type);
    }

    /**
     * Schematic id for a tier, falling back down to the highest defined tier
     * at or below the request. One building may legally serve every tier —
     * that is the shape a contest submission takes.
     */
    public String definitionIdFor(int tier) {
        for (int t = tier; t >= 1; t--) {
            String id = tiers.get(t);
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }
}
