package dev.branzx.idle.skin;

import dev.branzx.idle.schematic.SchematicDefinition;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Submissions are pasted into other players' worlds, so this is a security
 * boundary rather than a convenience check.
 */
class SkinValidatorTest {

    private static final SkinValidator.Limits LIMITS = SkinValidator.Limits.defaults();

    private static SchematicDefinition build(String... entries) {
        SchematicDefinition definition = new SchematicDefinition("submission");
        definition.getBlocks().addAll(List.of(entries));
        return definition;
    }

    /** A small legal cube: the baseline that must pass cleanly. */
    private static SchematicDefinition legalHut() {
        SchematicDefinition definition = new SchematicDefinition("hut");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 2; y++) {
                    definition.getBlocks().add(x + "," + y + "," + z + "|minecraft:oak_planks");
                }
            }
        }
        return definition;
    }

    private static boolean hasRule(List<SkinValidator.Violation> violations, String rule) {
        return violations.stream().anyMatch(v -> v.rule().equals(rule));
    }

    @Test
    void aLegalBuildPasses() {
        assertTrue(SkinValidator.validate(legalHut(), LIMITS).isEmpty());
    }

    @Test
    void anEmptyCaptureIsReportedOnItsOwn() {
        var violations = SkinValidator.validate(build(), LIMITS);

        assertEquals(1, violations.size(), "other rules would be noise on an empty capture");
        assertTrue(hasRule(violations, "empty"));
    }

    @Test
    void anAirOnlyCaptureCountsAsEmpty() {
        // A wrong base Y captures nothing but air; the message must point at
        // that rather than silently accepting a blank skin.
        var violations = SkinValidator.validate(
                build("0,0,0|minecraft:air", "1,0,0|minecraft:cave_air"), LIMITS);

        assertTrue(hasRule(violations, "empty"));
    }

    @Test
    void containersAreRejected() {
        var violations = SkinValidator.validate(
                build("0,0,0|minecraft:oak_planks", "1,0,0|minecraft:chest"), LIMITS);

        assertTrue(hasRule(violations, "banned-blocks"));
    }

    @Test
    void liquidsAreRejectedBecauseTheyFlowAfterPaste() {
        assertTrue(SkinValidator.isBanned(Material.WATER));
        assertTrue(SkinValidator.isBanned(Material.LAVA));
    }

    @Test
    void serverIntegrityBlocksAreRejected() {
        assertTrue(SkinValidator.isBanned(Material.SPAWNER));
        assertTrue(SkinValidator.isBanned(Material.COMMAND_BLOCK));
        assertTrue(SkinValidator.isBanned(Material.STRUCTURE_BLOCK));
        assertTrue(SkinValidator.isBanned(Material.BARRIER));
    }

    @Test
    void bedsAndShulkersAreRejectedByTag() {
        // Beds grant respawn rights inside someone else's claim; shulkers are
        // portable storage. Both are whole material families.
        assertTrue(SkinValidator.isBanned(Material.RED_BED));
        assertTrue(SkinValidator.isBanned(Material.WHITE_BED));
        assertTrue(SkinValidator.isBanned(Material.SHULKER_BOX));
        assertTrue(SkinValidator.isBanned(Material.LIME_SHULKER_BOX));
    }

    @Test
    void griefAndAreaEffectBlocksAreRejected() {
        assertTrue(SkinValidator.isBanned(Material.TNT));
        assertTrue(SkinValidator.isBanned(Material.BEACON));
        assertTrue(SkinValidator.isBanned(Material.RESPAWN_ANCHOR));
    }

    @Test
    void ordinaryBuildingBlocksAreAllowed() {
        assertFalse(SkinValidator.isBanned(Material.OAK_PLANKS));
        assertFalse(SkinValidator.isBanned(Material.STONE_BRICKS));
        assertFalse(SkinValidator.isBanned(Material.OAK_STAIRS));
        assertFalse(SkinValidator.isBanned(Material.OAK_DOOR));
        assertFalse(SkinValidator.isBanned(Material.TORCH));
        assertFalse(SkinValidator.isBanned(Material.GLASS));
    }

    @Test
    void anOversizedFootprintIsRejected() {
        var violations = SkinValidator.validate(
                build("-10,0,0|minecraft:stone", "10,0,0|minecraft:stone"), LIMITS);

        assertTrue(hasRule(violations, "footprint"));
    }

    @Test
    void anOverTallBuildIsRejected() {
        var violations = SkinValidator.validate(
                build("0,0,0|minecraft:stone", "0,150,0|minecraft:stone"), LIMITS);

        assertTrue(hasRule(violations, "height"));
    }

    @Test
    void exceedingTheBlockBudgetIsRejected() {
        SchematicDefinition dense = new SchematicDefinition("dense");
        for (int i = 0; i < 20; i++) {
            dense.getBlocks().add("0," + i + ",0|minecraft:stone");
        }
        var tightBudget = new SkinValidator.Limits(15, 15, 100, 10);

        assertTrue(hasRule(SkinValidator.validate(dense, tightBudget), "block-count"));
    }

    /** Air is free: only non-air blocks count against the budget. */
    @Test
    void airDoesNotCountTowardTheBlockBudget() {
        SchematicDefinition mostlyAir = new SchematicDefinition("airy");
        mostlyAir.getBlocks().add("0,0,0|minecraft:stone");
        for (int i = 1; i < 50; i++) {
            mostlyAir.getBlocks().add("0," + i + ",0|minecraft:air");
        }

        assertFalse(hasRule(SkinValidator.validate(mostlyAir,
                new SkinValidator.Limits(15, 15, 100, 5)), "block-count"));
    }

    @Test
    void everyBrokenRuleIsReportedAtOnce() {
        // Fixing one rule per capture would be miserable for a large build.
        var violations = SkinValidator.validate(
                build("-10,0,0|minecraft:chest", "10,150,0|minecraft:stone"), LIMITS);

        assertTrue(hasRule(violations, "footprint"));
        assertTrue(hasRule(violations, "height"));
        assertTrue(hasRule(violations, "banned-blocks"));
    }

    @Test
    void materialParsingHandlesNamespaceAndBlockStates() {
        assertEquals(Material.OAK_STAIRS,
                SkinValidator.materialOf("minecraft:oak_stairs[facing=north,half=bottom]"));
        assertEquals(Material.STONE, SkinValidator.materialOf("stone"));
        assertEquals(Material.CHEST, SkinValidator.materialOf("minecraft:chest[type=single]"));
        assertNull(SkinValidator.materialOf("minecraft:not_a_real_block"));
        assertNull(SkinValidator.materialOf(null));
    }

    /** A disguised container must not slip through on formatting alone. */
    @Test
    void bannedBlocksAreCaughtRegardlessOfBlockStateSuffix() {
        var violations = SkinValidator.validate(
                build("0,0,0|minecraft:oak_planks",
                        "1,0,0|minecraft:trapped_chest[facing=east,type=left]"), LIMITS);

        assertTrue(hasRule(violations, "banned-blocks"));
    }
}
