package dev.branzx.idlefarm.schematic;

/** Code-generated placeholder building shipped until real .schem assets exist. */
final class DefaultHut {

    private DefaultHut() {
    }

    static SchematicDefinition build() {
        SchematicDefinition definition = new SchematicDefinition(SchematicRegistry.DEFAULT_ID);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                definition.getBlocks().add(dx + ",-1," + dz + "|minecraft:oak_planks");
                definition.getBlocks().add(dx + ",3," + dz + "|minecraft:oak_slab");
                for (int dy = 0; dy <= 2; dy++) {
                    boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    boolean doorGap = dx == 0 && dz == 2 && dy <= 1;
                    String material;
                    if (corner) {
                        material = "minecraft:oak_log";
                    } else if (edge && !doorGap) {
                        material = "minecraft:oak_planks";
                    } else {
                        material = "minecraft:air";
                    }
                    definition.getBlocks().add(dx + "," + dy + "," + dz + "|" + material);
                }
            }
        }
        // Bed-like spawn anchors inside the hut, one per early slot.
        definition.getSpawnAnchors().add(new RelPos(-1, 0, -1));
        definition.getSpawnAnchors().add(new RelPos(1, 0, -1));
        definition.getSpawnAnchors().add(new RelPos(-1, 0, 1));
        definition.getSpawnAnchors().add(new RelPos(1, 0, 1));
        // Work-site out front.
        definition.getWorkAnchors().add(new RelPos(4, 0, 4));
        definition.getWorkAnchors().add(new RelPos(-4, 0, 4));
        definition.setWanderRadius(5);
        return definition;
    }
}
