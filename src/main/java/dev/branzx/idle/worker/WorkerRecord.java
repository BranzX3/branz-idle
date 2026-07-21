package dev.branzx.idle.worker;

import java.util.UUID;

public final class WorkerRecord {

    public static final String STATE_BAG = "BAG";
    public static final String STATE_ITEM = "ITEM";
    public static final String STATE_WORKING = "WORKING";
    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_STOP = "STOP";
    public static final String STATE_EXPLORING = "EXPLORING";

    private final UUID workerUuid;
    private volatile UUID ownerUuid; // bag owner; null when a free-floating item
    private final Rarity rarity;
    private final Trait trait;
    private volatile WorkerStats stats;
    private volatile String name;
    private volatile String skin;
    private volatile int level;
    private volatile long exp;
    private volatile Long assignedNodeId; // null = item-form or in bag
    private volatile String state;

    public WorkerRecord(UUID workerUuid, UUID ownerUuid, Rarity rarity, Trait trait, WorkerStats stats,
                        String name, String skin, int level, long exp, Long assignedNodeId, String state) {
        this.workerUuid = workerUuid;
        this.ownerUuid = ownerUuid;
        this.rarity = rarity;
        this.trait = trait;
        this.stats = stats;
        this.name = name;
        this.skin = skin;
        this.level = level;
        this.exp = exp;
        this.assignedNodeId = assignedNodeId;
        this.state = state;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public boolean isInBag() {
        return STATE_BAG.equals(state);
    }

    public String getSkin() {
        return skin;
    }

    public void setSkin(String skin) {
        this.skin = skin;
    }

    public UUID getWorkerUuid() {
        return workerUuid;
    }

    public Rarity getRarity() {
        return rarity;
    }

    public Trait getTrait() {
        return trait;
    }

    public WorkerStats getStats() {
        return stats;
    }

    public void setStats(WorkerStats stats) {
        this.stats = stats;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getExp() {
        return exp;
    }

    public void setExp(long exp) {
        this.exp = exp;
    }

    public Long getAssignedNodeId() {
        return assignedNodeId;
    }

    public void setAssignedNodeId(Long assignedNodeId) {
        this.assignedNodeId = assignedNodeId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public boolean isItemForm() {
        return assignedNodeId == null;
    }
}
