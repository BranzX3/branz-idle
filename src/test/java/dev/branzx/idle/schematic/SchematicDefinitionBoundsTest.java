package dev.branzx.idle.schematic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchematicDefinitionBoundsTest {

    private static SchematicDefinition of(String... entries) {
        SchematicDefinition definition = new SchematicDefinition("test");
        definition.getBlocks().addAll(java.util.List.of(entries));
        return definition;
    }

    @Test
    void boundsSpanEveryBlockInclusive() {
        SchematicDefinition.Bounds bounds = of(
                "-2,0,-2|minecraft:stone",
                "2,3,1|minecraft:stone",
                "0,1,4|minecraft:stone").bounds();

        assertEquals(-2, bounds.minX());
        assertEquals(2, bounds.maxX());
        assertEquals(0, bounds.minY());
        assertEquals(3, bounds.maxY());
        assertEquals(-2, bounds.minZ());
        assertEquals(4, bounds.maxZ());
        assertEquals(5, bounds.width());
        assertEquals(4, bounds.height());
        assertEquals(7, bounds.depth());
    }

    @Test
    void singleBlockDefinitionHasUnitExtent() {
        SchematicDefinition.Bounds bounds = of("3,3,3|minecraft:stone").bounds();

        assertEquals(1, bounds.width());
        assertEquals(1, bounds.height());
        assertEquals(1, bounds.depth());
    }

    /** A zero box keeps callers (ground sampling, survey) from looping wildly. */
    @Test
    void emptyDefinitionCollapsesToOrigin() {
        SchematicDefinition.Bounds bounds = of().bounds();

        assertEquals(0, bounds.minX());
        assertEquals(0, bounds.maxZ());
        assertEquals(1, bounds.width());
    }

    /** Malformed lines are skipped rather than sizing the box to garbage. */
    @Test
    void malformedEntriesAreIgnored() {
        SchematicDefinition.Bounds bounds = of(
                "1,1,1|minecraft:stone",
                "no-pipe-here",
                "4,4|minecraft:stone",
                "x,y,z|minecraft:stone").bounds();

        assertEquals(1, bounds.minX());
        assertEquals(1, bounds.maxX());
        assertEquals(1, bounds.width());
    }

    @Test
    void defaultHutIsCenteredOnItsOrigin() {
        SchematicDefinition.Bounds bounds = DefaultHut.build().bounds();

        // Symmetry in XZ is what makes rotation about the origin safe.
        assertEquals(-bounds.minX(), bounds.maxX());
        assertEquals(-bounds.minZ(), bounds.maxZ());
    }
}
