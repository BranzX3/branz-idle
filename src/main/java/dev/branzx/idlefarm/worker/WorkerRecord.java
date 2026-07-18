package dev.branzx.idlefarm.worker;

import java.util.UUID;

public final class WorkerRecord {

    public static final String STATE_ITEM = "ITEM";
    public static final String STATE_WORKING = "WORKING";
    public static final String STATE_IDLE = "IDLE";
    public static final String STATE_STOP = "STOP";
    public static final String STATE_EXPLORING = "EXPLORING";

    private final UUID workerUuid;
    private final Rarity rarity;
    private final Trait trait;
    private volatile WorkerStats stats;
    private final String name;
    private volatile int level;
    private volatile long exp;
    private volatile Long assignedNodeId; // null = item-form
    private volatile String state;

    public WorkerRecord(UUID workerUuid, Rarity rarity, Trait trait, WorkerStats stats,
                        String name, int level, long exp, Long assignedNodeId, String state) {
        this.workerUuid = workerUuid;
        this.rarity = rarity;
        this.trait = trait;
        this.stats = stats;
        this.name = name;
        this.level = level;
        this.exp = exp;
        this.assignedNodeId = assignedNodeId;
        this.state = state;
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
