package dev.branzx.idle.service.design;

import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.node.NodeType;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.GameDesignService.Result;
import dev.branzx.idle.storage.GameStateStore;
import dev.branzx.idle.storage.NodeStore;

import java.util.UUID;

/**
 * The Focused Node choice and first-claim onboarding markers. Focus gates the
 * daily commission cadence, so changes are cooldown-limited and audited.
 */
public final class FocusService {

    private static final long FOCUS_COOLDOWN_MS = 24L * 60 * 60 * 1000;

    private final GameStateStore state;
    private final NodeStore nodeStore;
    private final AuditService audit;
    private final TelemetryService telemetry;

    public FocusService(GameStateStore state, NodeStore nodeStore, AuditService audit,
                        TelemetryService telemetry) {
        this.state = state;
        this.nodeStore = nodeStore;
        this.audit = audit;
        this.telemetry = telemetry;
    }

    public Long focusedNode(UUID owner) {
        String value = state.get(owner, "ACCOUNT", "-", "focused_node");
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public boolean isFocused(NodeRecord node) {
        Long focused = focusedNode(node.getOwnerUuid());
        return focused != null && focused == node.getId();
    }

    public NodeRecord focusedRecord(UUID owner) {
        Long id = focusedNode(owner);
        if (id == null) return null;
        return nodeStore.getByOwner(owner).stream()
                .filter(n -> n.getId() == id).findFirst().orElse(null);
    }

    public Result setFocus(UUID owner, NodeRecord node, boolean freeOverride) {
        if (node == null || !node.getOwnerUuid().equals(owner) || !node.getType().isProduction()) {
            return Result.fail("Choose one of your Production Nodes.");
        }
        long changedAt = state.getLong(owner, "ACCOUNT", "-", "focus_changed_at", 0);
        long remaining = FOCUS_COOLDOWN_MS - (System.currentTimeMillis() - changedAt);
        if (!freeOverride && changedAt > 0 && remaining > 0) {
            return Result.fail("Focus can change again in " + DesignText.formatDuration(remaining) + ".");
        }
        state.put(owner, "ACCOUNT", "-", "focused_node", String.valueOf(node.getId()));
        state.put(owner, "ACCOUNT", "-", "focus_changed_at", String.valueOf(System.currentTimeMillis()));
        audit.log(owner, "FOCUS_CHANGE", "{\"node\":" + node.getId() + "}");
        telemetry.record(owner, "FOCUS_CHANGE", "{\"node\":" + node.getId() + "}");
        return Result.ok("Focused Node set to " + node.getType() + " Lv." + node.getExplorationLevel() + ".");
    }

    // ---- onboarding markers ---------------------------------------------------

    public boolean firstResidentialIsFree(UUID owner) {
        return nodeStore.getByOwner(owner).stream().noneMatch(n -> n.getType() == NodeType.RESIDENTIAL);
    }

    public boolean firstProductionIsFree(UUID owner) {
        return nodeStore.getByOwner(owner).stream().noneMatch(n -> n.getType().isProduction());
    }

    public boolean isStarterProductionClaimed(UUID owner) {
        return "1".equals(state.get(owner, "ACCOUNT", "-", "starter_production"));
    }

    public void markStarterProduction(UUID owner) {
        state.put(owner, "ACCOUNT", "-", "starter_production", "1");
    }

    public void incrementProductionType(NodeRecord node) {
        state.increment(node.getOwnerUuid(), "ACCOUNT", "-",
                "production_types_" + node.getType().name(), 1);
    }
}
