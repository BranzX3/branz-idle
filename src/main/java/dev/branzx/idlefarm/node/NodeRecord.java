package dev.branzx.idlefarm.node;

import java.util.UUID;

public final class NodeRecord {

    private final long id;
    private final UUID ownerUuid;
    private final ChunkKey chunk;
    private volatile NodeType type;
    private volatile int tier;
    private volatile String state;

    public NodeRecord(long id, UUID ownerUuid, ChunkKey chunk, NodeType type, int tier, String state) {
        this.id = id;
        this.ownerUuid = ownerUuid;
        this.chunk = chunk;
        this.type = type;
        this.tier = tier;
        this.state = state;
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
