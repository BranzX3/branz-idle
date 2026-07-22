package dev.branzx.idle.schematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A building blueprint: block data plus admin-authored metadata (NPC spawn
 * anchors per worker slot, work-site anchors, wander zone, animation
 * profiles per state). One definition serves every node using it.
 */
public final class SchematicDefinition {

    private final String id;
    /** "dx,dy,dz|blockdata" entries relative to origin. */
    private final List<String> blocks = new ArrayList<>();
    /** Index = worker slot - 1. */
    private final List<RelPos> spawnAnchors = new ArrayList<>();
    private final List<RelPos> workAnchors = new ArrayList<>();
    private int wanderRadius = 5;
    /** state name (WORKING/IDLE/STOP) -> animation profile name in config. */
    private final Map<String, String> profiles = new LinkedHashMap<>();

    public SchematicDefinition(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public List<String> getBlocks() {
        return blocks;
    }

    public List<RelPos> getSpawnAnchors() {
        return spawnAnchors;
    }

    public List<RelPos> getWorkAnchors() {
        return workAnchors;
    }

    public int getWanderRadius() {
        return wanderRadius;
    }

    public void setWanderRadius(int wanderRadius) {
        this.wanderRadius = wanderRadius;
    }

    public Map<String, String> getProfiles() {
        return profiles;
    }

    /**
     * Spawn anchor for a slot; slots beyond the authored anchors fall back
     * to an auto-layout line in front of the building.
     */
    public RelPos spawnAnchorOrFallback(int slotIndex) {
        if (slotIndex < spawnAnchors.size()) {
            return spawnAnchors.get(slotIndex);
        }
        return new RelPos((slotIndex % 3) - 1, 0, 3 + (slotIndex / 3));
    }

    /** Work anchor for a slot; falls back to the spawn anchor if unset. */
    public RelPos workAnchorOrFallback(int slotIndex) {
        if (slotIndex < workAnchors.size() && workAnchors.get(slotIndex) != null) {
            return workAnchors.get(slotIndex);
        }
        return spawnAnchorOrFallback(slotIndex);
    }

    /** Origin-relative extent of a definition; empty definitions give a zero box. */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }
    }

    /**
     * Computes the bounding box from the block list. Recomputed per call
     * rather than cached: the block list is mutable and exposed, so a cache
     * would go stale the moment an admin edits a definition in place.
     */
    public Bounds bounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (String entry : blocks) {
            int pipe = entry.indexOf('|');
            if (pipe < 0) {
                continue;
            }
            String[] rel = entry.substring(0, pipe).split(",");
            if (rel.length < 3) {
                continue;
            }
            int x, y, z;
            try {
                x = Integer.parseInt(rel[0].trim());
                y = Integer.parseInt(rel[1].trim());
                z = Integer.parseInt(rel[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            any = true;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        return any ? new Bounds(minX, minY, minZ, maxX, maxY, maxZ)
                : new Bounds(0, 0, 0, 0, 0, 0);
    }

    /** Set a per-slot anchor in a list, growing with nulls as needed. */
    public static void setSlot(List<RelPos> anchors, int slotIndex, RelPos pos) {
        while (anchors.size() <= slotIndex) {
            anchors.add(null);
        }
        anchors.set(slotIndex, pos);
    }
}
