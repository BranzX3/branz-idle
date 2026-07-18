package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.node.TrustLevel;
import dev.branzx.idlefarm.storage.NodeStore;

import java.util.UUID;

public final class TrustService {

    private final NodeStore nodeStore;

    public TrustService(NodeStore nodeStore) {
        this.nodeStore = nodeStore;
    }

    /** True if {@code actor} may build/interact inside {@code node}'s chunk. */
    public boolean canBuild(UUID actor, NodeRecord node) {
        if (node.getOwnerUuid().equals(actor)) {
            return true;
        }
        TrustLevel level = nodeStore.getTrust(node.getOwnerUuid(), actor);
        return level != null && level.atLeast(TrustLevel.HELPER);
    }

    /** True if {@code actor} may collect buffers / view GUIs of the territory. */
    public boolean canHelp(UUID actor, UUID owner) {
        if (owner.equals(actor)) {
            return true;
        }
        TrustLevel level = nodeStore.getTrust(owner, actor);
        return level != null && level.atLeast(TrustLevel.HELPER);
    }

    /** True if {@code actor} may manage workers / start exploration events. */
    public boolean canManage(UUID actor, UUID owner) {
        if (owner.equals(actor)) {
            return true;
        }
        TrustLevel level = nodeStore.getTrust(owner, actor);
        return level != null && level.atLeast(TrustLevel.MANAGER);
    }

    public void setTrust(UUID owner, UUID trusted, TrustLevel level) {
        nodeStore.setTrustSync(owner, trusted, level);
    }

    public void removeTrust(UUID owner, UUID trusted) {
        nodeStore.removeTrustSync(owner, trusted);
    }
}
