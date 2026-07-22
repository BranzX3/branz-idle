package dev.branzx.idle.skin;

import dev.branzx.idle.schematic.SchematicDefinition;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a player-submitted building before it can become a skin.
 *
 * <p>An approved skin is pasted into other players' worlds, so submissions are
 * validated far more strictly than admin captures. This catches block-level
 * abuse only — it cannot judge whether a shape traps NPCs, walls in a
 * neighbour, or is offensive, which is why manual review stays mandatory.</p>
 *
 * <p>Pure logic with no server dependency, so the rules are directly
 * testable.</p>
 */
public final class SkinValidator {

    /** Limits, so callers can source them from config. */
    public record Limits(int maxWidth, int maxDepth, int maxHeight, int maxBlocks) {
        public static Limits defaults() {
            return new Limits(15, 15, 100, 6000);
        }
    }

    public record Violation(String rule, String detail) {
        @Override
        public String toString() {
            return rule + ": " + detail;
        }
    }

    /**
     * Blocks that may never appear in a submission, with the reason each is
     * banned recorded next to it.
     */
    private static final Set<Material> BANNED = EnumSet.of(
            // Hide items and conflict with node storage.
            Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST, Material.BARREL,
            Material.HOPPER, Material.DISPENSER, Material.DROPPER, Material.FURNACE,
            Material.BLAST_FURNACE, Material.SMOKER, Material.BREWING_STAND,
            Material.CRAFTER, Material.DECORATED_POT, Material.LECTERN, Material.JUKEBOX,
            // Server integrity.
            Material.SPAWNER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK, Material.JIGSAW,
            Material.BARRIER, Material.LIGHT, Material.END_PORTAL_FRAME,
            // Flow and destroy neighbouring terrain after the paste.
            Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
            // Grief vectors and effects the node owner did not choose.
            Material.TNT, Material.BEACON, Material.RESPAWN_ANCHOR);

    private SkinValidator() {
    }

    /** Every rule a submission breaks; empty means it may go to review. */
    public static List<Violation> validate(SchematicDefinition definition, Limits limits) {
        List<Violation> violations = new ArrayList<>();
        SchematicDefinition.Bounds bounds = definition.bounds();
        int solid = 0;
        Set<Material> offenders = EnumSet.noneOf(Material.class);

        for (String entry : definition.getBlocks()) {
            int pipe = entry.indexOf('|');
            if (pipe < 0) {
                continue;
            }
            Material material = materialOf(entry.substring(pipe + 1));
            if (material == null || isAir(material)) {
                continue;
            }
            solid++;
            if (isBanned(material)) {
                offenders.add(material);
            }
        }

        if (definition.getBlocks().isEmpty() || solid == 0) {
            violations.add(new Violation("empty",
                    "the capture contains no blocks — check your base Y and height"));
            return violations; // every other rule would be noise
        }
        if (bounds.width() > limits.maxWidth() || bounds.depth() > limits.maxDepth()) {
            violations.add(new Violation("footprint",
                    bounds.width() + "×" + bounds.depth() + " exceeds "
                            + limits.maxWidth() + "×" + limits.maxDepth()));
        }
        if (bounds.height() > limits.maxHeight()) {
            violations.add(new Violation("height",
                    bounds.height() + " exceeds " + limits.maxHeight()));
        }
        if (solid > limits.maxBlocks()) {
            violations.add(new Violation("block-count",
                    solid + " blocks exceeds " + limits.maxBlocks()));
        }
        if (!offenders.isEmpty()) {
            violations.add(new Violation("banned-blocks",
                    offenders.stream().map(Enum::name).sorted()
                            .collect(java.util.stream.Collectors.joining(", "))));
        }
        return violations;
    }

    /**
     * Enum comparison rather than {@code Material#isAir}, which resolves
     * through the block registry and so needs a running server. There are
     * exactly three air materials, so this is equally exact.
     */
    static boolean isAir(Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }

    /**
     * Two whole material families are banned as well as the explicit set:
     * beds (they grant respawn rights inside another player's claim) and
     * shulker boxes (portable storage).
     *
     * <p>Matched by name rather than {@code Tag.BEDS} / {@code
     * Tag.SHULKER_BOXES}, which resolve through the running server. Every
     * vanilla bed ends in {@code _BED} and every shulker box ends in
     * {@code SHULKER_BOX}, so the match is exact and keeps this class
     * testable.</p>
     */
    public static boolean isBanned(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return BANNED.contains(material)
                || name.endsWith("_BED")
                || name.endsWith("SHULKER_BOX");
    }

    /**
     * Material name out of a block-data string such as
     * {@code minecraft:oak_stairs[facing=north]}. Enum lookup rather than the
     * registry-backed matcher keeps this class free of server state.
     */
    static Material materialOf(String blockData) {
        if (blockData == null) {
            return null;
        }
        String name = blockData.trim();
        int bracket = name.indexOf('[');
        if (bracket >= 0) {
            name = name.substring(0, bracket);
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return Material.getMaterial(name.toUpperCase(Locale.ROOT));
    }
}
