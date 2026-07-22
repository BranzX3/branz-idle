package dev.branzx.idle.node;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeRecord {
    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_STORAGE_FULL = "STORAGE_FULL";


    private final long id;
    private final UUID ownerUuid;
    private final ChunkKey chunk;
    private volatile NodeType type;
    private volatile int tier;
    private volatile String state;
    /**
     * Building origin Y captured at claim time and never recomputed, so later
     * terrain edits cannot move an existing building.
     *
     * <p>The one legitimate change is a Residential plot joining a Complex: it
     * adopts the anchor's level, because a Complex whose chunks each found
     * their own ground would step up and down a slope and tear apart.</p>
     */
    private volatile int originY;
    /** Anchor for lazy discovery-lane accrual (epoch millis). */
    private volatile long lastTickAt;
    /** Anchor for lazy bulk-lane accrual (epoch millis). */
    private volatile long bulkLastTickAt;
    private volatile int explorationLevel;
    private volatile long explorationExp;
    /** Epoch millis a tier upgrade completes; 0 = not upgrading. */
    private volatile long upgradeEndsAt;
    /** Selected building skin id; null = the server default for this type. */
    private volatile String skinId;
    /** Building orientation in clockwise quarter-turns, 0-3. */
    private volatile int rotation;
    /**
     * Id of the Production node anchoring the Complex this node belongs to;
     * 0 when it stands alone. The anchor points at itself.
     */
    private volatile long complexAnchor;
    /** Buffered uncollected discovery-lane output: material name -> count. */
    private final Map<String, Integer> storage = new ConcurrentHashMap<>();
    /** Buffered uncollected bulk-lane commons: material name -> count. */
    private final Map<String, Integer> bulkStorage = new ConcurrentHashMap<>();

    public NodeRecord(long id, UUID ownerUuid, ChunkKey chunk, NodeType type, int tier, String state,
                      int originY, long lastTickAt, String storageSerialized) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.chunk = chunk;
        this.type = type;
        this.tier = tier;
        this.state = state;
        this.originY = originY;
        this.lastTickAt = lastTickAt;
        this.bulkLastTickAt = lastTickAt;
        deserializeInto(storage, storageSerialized);
    }

    private static void deserializeInto(Map<String, Integer> target, String serialized) {
        if (serialized != null && !serialized.isBlank()) {
            for (String entry : serialized.split(";")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    target.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
                }
            }
        }
    }

    public int getOriginY() {
        return originY;
    }

    /** Only for a Residential plot adopting its Complex anchor's level. */
    public void setOriginY(int originY) {
        this.originY = originY;
    }

    public long getLastTickAt() {
        return lastTickAt;
    }

    public void setLastTickAt(long lastTickAt) {
        this.lastTickAt = lastTickAt;
    }

    public int getExplorationLevel() {
        return explorationLevel;
    }

    public void setExplorationLevel(int explorationLevel) {
        this.explorationLevel = explorationLevel;
    }

    public long getExplorationExp() {
        return explorationExp;
    }

    public void setExplorationExp(long explorationExp) {
        this.explorationExp = explorationExp;
    }

    public long getUpgradeEndsAt() {
        return upgradeEndsAt;
    }

    public void setUpgradeEndsAt(long upgradeEndsAt) {
        this.upgradeEndsAt = upgradeEndsAt;
    }

    public boolean isUpgrading() {
        return upgradeEndsAt > 0;
    }

    public String getSkinId() {
        return skinId;
    }

    /** Blank ids normalize to null so "default" has exactly one representation. */
    public void setSkinId(String skinId) {
        this.skinId = skinId == null || skinId.isBlank() ? null : skinId;
    }

    public int getRotation() {
        return rotation;
    }

    /** Wraps into 0-3; callers may pass any accumulated quarter-turn count. */
    public void setRotation(int rotation) {
        this.rotation = Math.floorMod(rotation, 4);
    }

    public long getComplexAnchor() {
        return complexAnchor;
    }

    public void setComplexAnchor(long complexAnchor) {
        this.complexAnchor = Math.max(0, complexAnchor);
    }

    public boolean isInComplex() {
        return complexAnchor > 0;
    }

    /** True for the Production node that anchors its own Complex. */
    public boolean isComplexAnchor() {
        return complexAnchor > 0 && complexAnchor == id;
    }

    public Map<String, Integer> getStorage() {
        return storage;
    }

    public int storageTotal() {
        return storage.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String serializeStorage() {
        return serialize(storage);
    }

    public Map<String, Integer> getBulkStorage() {
        return bulkStorage;
    }

    public int bulkStorageTotal() {
        return bulkStorage.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String serializeBulkStorage() {
        return serialize(bulkStorage);
    }

    /** Replaces the bulk buffer with a persisted snapshot (load path only). */
    public void loadBulkStorage(String serialized) {
        bulkStorage.clear();
        deserializeInto(bulkStorage, serialized);
    }

    public long getBulkLastTickAt() {
        return bulkLastTickAt;
    }

    public void setBulkLastTickAt(long bulkLastTickAt) {
        this.bulkLastTickAt = bulkLastTickAt;
    }

    private static String serialize(Map<String, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    public long getId() {
        return id;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public ChunkKey getChunk() {
        return chunk;
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public int getTier() {
        return tier;
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
