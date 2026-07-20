package dev.branzx.idlefarm.storage;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerData {

    private final UUID uuid;
    private volatile String name;
    private final AtomicReference<Double> balance;
    private volatile long totalOnlineMinutes;
    private volatile boolean dirty;
    private final AtomicLong revision = new AtomicLong();

    public PlayerData(UUID uuid, String name, double balance, long totalOnlineMinutes) {
        this.uuid = uuid;
        this.name = name;
        this.balance = new AtomicReference<>(balance);
        this.totalOnlineMinutes = totalOnlineMinutes;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        markDirty();
    }

    public double getBalance() {
        return balance.get();
    }

    public void addBalance(double amount) {
        balance.updateAndGet(v -> v + amount);
        markDirty();
    }

    public long getTotalOnlineMinutes() {
        return totalOnlineMinutes;
    }

    public void incrementOnlineMinutes(long minutes) {
        this.totalOnlineMinutes += minutes;
        markDirty();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public long getRevision() {
        return revision.get();
    }

    public void markPersisted(long savedRevision) {
        if (revision.get() == savedRevision) {
            dirty = false;
        }
    }

    private void markDirty() {
        revision.incrementAndGet();
        dirty = true;
    }
}
