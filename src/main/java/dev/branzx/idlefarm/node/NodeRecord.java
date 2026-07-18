package dev.branzx.idlefarm.node;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NodeRecord {

    private final long id;
    private final UUID ownerUuid;
    private final ChunkKey chunk;
    private volatile NodeType type;
    private volatile int tier;
    private volatile String state;
    /** Building origin Y captured at claim time (stable across terrain edits). */
    private final int originY;
    /** Anchor for lazy production accrual (epoch millis). */
    private volatile long lastTickAt;
    /** Buffered uncollected output: material name -> count. */
    private final Map<String, Integer> storage = new ConcurrentHashMap<>();

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
        if (storageSerialized != null && !storageSerialized.isBlank()) {
            for (String entry : storageSerialized.split(";")) {
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    storage.put(entry.substring(0, colon), Integer.parseInt(entry.substring(colon + 1)));
                }
            }
        }
    }

    public int getOriginY() {
        return originY;
    }

    public long getLastTickAt() {
        return lastTickAt;
    }

    public void setLastTickAt(long lastTickAt) {
        this.lastTickAt = lastTickAt;
    }

    public Map<String, Integer> getStorage() {
        return storage;
    }

    public int storageTotal() {
        return storage.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String serializeStorage() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : storage.entrySet()) {
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
