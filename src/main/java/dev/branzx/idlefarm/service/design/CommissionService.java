package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.node.NodeRecord;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.GameDesignService.Commission;
import dev.branzx.idlefarm.service.GameDesignService.Result;
import dev.branzx.idlefarm.service.ProgressionRewards;
import dev.branzx.idlefarm.service.WarehouseService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import org.bukkit.Material;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The daily commission cadence for the Focused Node, the Weekly Chapter and
 * the returning-player catch-up slot. Also records the per-node lifetime
 * counters used by the daily reward gates.
 */
public final class CommissionService {

    private final Database database;
    private final GameStateStore state;
    private final AuditService audit;
    private final TelemetryService telemetry;
    private final ProgressionRewards rewards;
    private final FocusService focus;
    private final NodeBuildService builds;
    private final ChronicleService chronicle;
    private final WarehouseService warehouse;
    private final NodeExpSink nodeExp;
    private final CoinSink coins;

    public CommissionService(Database database, GameStateStore state, AuditService audit,
                             TelemetryService telemetry, ProgressionRewards rewards,
                             FocusService focus, NodeBuildService builds, ChronicleService chronicle,
                             WarehouseService warehouse, NodeExpSink nodeExp, CoinSink coins) {
        this.database = database;
        this.state = state;
        this.audit = audit;
        this.telemetry = telemetry;
        this.rewards = rewards;
        this.focus = focus;
        this.builds = builds;
        this.chronicle = chronicle;
        this.warehouse = warehouse;
        this.nodeExp = nodeExp;
        this.coins = coins;
    }

    /** Tracks missed days into simplified catch-up commissions. */
    public void onLogin(UUID owner) {
        String previous = state.get(owner, "ACCOUNT", "-", "last_login_day");
        LocalDate today = GameClock.today();
        if (previous != null) {
            try {
                long missed = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(previous), today) - 1;
                if (missed > 0) {
                    int simplified = (int) Math.min(3, missed);
                    state.put(owner, "ACCOUNT", "-", "catchup_commissions", String.valueOf(simplified));
                }
            } catch (Exception ignored) {
                // A malformed legacy date should never block login.
            }
        }
        state.put(owner, "ACCOUNT", "-", "last_login_day", today.toString());
    }

    // ---- reward cadence hooks -------------------------------------------------

    public void onBufferCollected(NodeRecord node, int amount) {
        UUID owner = node.getOwnerUuid();
        String day = GameClock.dayKey();
        if (focus.isFocused(node) && state.claimOnce(owner, "DAILY", day, "first_collection")) {
            nodeExp.grant(node, rewards.firstCollectionExp());
        }
        state.increment(owner, "NODE", String.valueOf(node.getId()), "collected_total", amount);
        advance(node, "collect", amount);
    }

    public void onItemsProduced(NodeRecord node, int total) {
        state.increment(node.getOwnerUuid(), "NODE", String.valueOf(node.getId()),
                "produced_total", total);
        advance(node, "produce", total);
    }

    public void onExplorationClaimed(NodeRecord node, String grade) {
        UUID owner = node.getOwnerUuid();
        String day = GameClock.dayKey();
        long exp;
        if (focus.isFocused(node) && state.claimOnce(owner, "DAILY", day, "first_expedition")) {
            exp = rewards.firstExpeditionExp();
        } else {
            int extra = state.getInt(owner, "DAILY", day, "extra_expeditions", 0);
            exp = extra < rewards.extraExpeditionsPerDay()
                    ? rewards.extraExpeditionExp() : 0;
            if (extra < rewards.extraExpeditionsPerDay()) {
                state.put(owner, "DAILY", day, "extra_expeditions", String.valueOf(extra + 1));
            }
        }
        nodeExp.grant(node, Math.round(exp * builds.eventExpMultiplier(node)));
        state.increment(owner, "NODE", String.valueOf(node.getId()), "events_total", 1);
        advance(node, "expedition", 1);
        if ("GREAT".equals(grade) || "JACKPOT".equals(grade)) {
            state.put(owner, "NODE", String.valueOf(node.getId()), "great_event", "1");
        }
    }

    public void advance(NodeRecord node, String action, int amount) {
        if (!focus.isFocused(node)) return;
        UUID owner = node.getOwnerUuid();
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, node, day);
        if ("expedition".equals(action) || "crew".equals(action)) {
            state.put(owner, "DAILY", day, "commission_behavior_progress", "1");
        }
        state.put(owner, "DAILY", day, "commission_focus_progress", "1");
        // A Weekly Chapter asks for activity across five distinct days, not
        // five rapid actions in one session.
        if (state.claimOnce(owner, "DAILY", day, "weekly_chapter_action")) {
            int weekly = state.getInt(owner, "WEEKLY", GameClock.weekKey(), "chapter_progress", 0);
            state.put(owner, "WEEKLY", GameClock.weekKey(), "chapter_progress",
                    String.valueOf(Math.min(5, weekly + 1)));
        }
    }

    // ---- queries and claims ---------------------------------------------------

    public List<Commission> commissions(UUID owner) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return List.of();
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, focused, day);
        List<Commission> result = new ArrayList<>(List.of(
                commission(owner, day, "focus", "Advance your Focused Node", 1,
                        rewards.focusCommissionExp() + " Node EXP"),
                commission(owner, day, "behavior", "Complete a worker or expedition action", 1,
                        rewards.behaviorCommissionCoins() + " Coins"),
                commission(owner, day, "supply", "Deliver 64 mixed unlocked resources", 64,
                        rewards.supplyCommissionChroniclePoints() + " Chronicle Points")));
        int catchup = state.getInt(owner, "ACCOUNT", "-", "catchup_commissions", 0);
        if (catchup > 0) {
            result.add(new Commission("catchup", "Returning-player simplified commission",
                    1, 1, rewards.catchupCommissionExp() + " Node EXP (catch-up)",
                    "1".equals(state.get(owner, "DAILY", day, "commission_catchup_claimed"))));
        }
        return List.copyOf(result);
    }

    public Result claimCommission(UUID owner, String slot) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return Result.fail("Select a Focused Node first.");
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, focused, day);
        Commission commission = commissions(owner).stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(slot)).findFirst().orElse(null);
        if (commission == null) return Result.fail("Unknown commission slot.");
        if (commission.claimed()) return Result.fail("That commission was already claimed.");
        if (commission.current() < commission.target()) return Result.fail("Commission is not complete yet.");
        switch (slot.toLowerCase(Locale.ROOT)) {
            case "focus" -> {
                nodeExp.grant(focused, rewards.focusCommissionExp());
                state.put(owner, "DAILY", day, "commission_focus_claimed", "1");
            }
            case "behavior" -> {
                coins.add(owner, rewards.behaviorCommissionCoins());
                state.put(owner, "DAILY", day, "commission_behavior_claimed", "1");
            }
            case "supply" -> {
                Result supply = settleSupply(owner, day);
                if (supply != null) return supply;
            }
            case "catchup" -> {
                int available = state.getInt(owner, "ACCOUNT", "-", "catchup_commissions", 0);
                if (available <= 0) return Result.fail("No catch-up commission is available.");
                nodeExp.grant(focused, rewards.catchupCommissionExp());
                state.put(owner, "ACCOUNT", "-", "catchup_commissions", String.valueOf(available - 1));
                state.put(owner, "DAILY", day, "commission_catchup_claimed", "1");
            }
            default -> { return Result.fail("Unknown commission slot."); }
        }
        audit.log(owner, "COMMISSION_CLAIM", "{\"slot\":\"" + DesignText.safe(slot)
                + "\",\"day\":\"" + day + "\"}");
        telemetry.record(owner, "COMMISSION_CLAIM", "{\"slot\":\"" + DesignText.safe(slot) + "\"}");
        return Result.ok("Commission reward claimed.");
    }

    /**
     * Consumes 64 mixed resources and grants the Chronicle Points; the
     * Warehouse row, claim flag and point gain commit in one transaction.
     * Returns a failure Result, or null when the settlement was queued.
     */
    private Result settleSupply(UUID owner, String day) {
        Map<String, Integer> contents = warehouse.getContents(owner);
        int available = contents.entrySet().stream()
                .filter(entry -> isCommissionSupply(entry.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        if (available < 64) {
            return Result.fail("Warehouse no longer has the required 64 resources.");
        }
        int remaining = 64;
        for (Map.Entry<String, Integer> entry : contents.entrySet()) {
            if (remaining <= 0) break;
            if (!isCommissionSupply(entry.getKey())) continue;
            remaining -= warehouse.prepareWithdraw(owner, entry.getKey(),
                    Math.min(remaining, entry.getValue()));
        }
        WarehouseService.Snapshot snapshot = warehouse.snapshot(owner);
        List<GameStateStore.Row> rows = List.of(
                state.stage(owner, "DAILY", day, "commission_supply_claimed", "1"),
                chronicle.stagePointsGain(owner, rewards.supplyCommissionChroniclePoints()));
        database.submitTransaction("supply commission " + owner, connection -> {
            WarehouseService.write(connection, snapshot);
            for (GameStateStore.Row row : rows) {
                GameStateStore.write(connection, row);
            }
        });
        return null;
    }

    public Result claimWeeklyChapter(UUID owner) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return Result.fail("Select a Focused Node first.");
        String week = GameClock.weekKey();
        int progress = state.getInt(owner, "WEEKLY", week, "chapter_progress", 0);
        if (progress < 5) return Result.fail("Weekly Chapter needs 5 daily actions (" + progress + "/5).");
        if (!state.claimOnce(owner, "WEEKLY", week, "chapter_claimed")) {
            return Result.fail("Weekly Chapter already claimed.");
        }
        nodeExp.grant(focused, rewards.weeklyChapterExp());
        coins.add(owner, rewards.weeklyChapterCoins());
        audit.log(owner, "CHAPTER_CLAIM", "{\"week\":\"" + week + "\"}");
        return Result.ok("Weekly Node Chapter claimed: +" + rewards.weeklyChapterExp()
                + " Node EXP and +" + rewards.weeklyChapterCoins() + " Coins.");
    }

    private void ensureDailyCommissions(UUID owner, NodeRecord focused, String day) {
        if (state.get(owner, "DAILY", day, "commission_seed") != null) return;
        state.put(owner, "DAILY", day, "commission_seed",
                focused.getType().name() + ":" + Math.max(1, focused.getExplorationLevel() / 10 + 1));
        state.put(owner, "DAILY", day, "commission_focus_progress", "0");
        state.put(owner, "DAILY", day, "commission_behavior_progress", "0");
        state.put(owner, "DAILY", day, "commission_supply_progress", "0");
    }

    private Commission commission(UUID owner, String day, String slot, String description,
                                  int defaultTarget, String reward) {
        int target = "supply".equals(slot) ? 64 : defaultTarget;
        int progress = "supply".equals(slot)
                ? Math.min(target, warehouse.getContents(owner).entrySet().stream()
                        .filter(entry -> isCommissionSupply(entry.getKey()))
                        .mapToInt(Map.Entry::getValue).sum())
                : state.getInt(owner, "DAILY", day, "commission_" + slot + "_progress", 0);
        boolean claimed = "1".equals(state.get(owner, "DAILY", day, "commission_" + slot + "_claimed"));
        return new Commission(slot, description, progress, target, reward, claimed);
    }

    private boolean isCommissionSupply(String material) {
        String id = material.toUpperCase(Locale.ROOT);
        return !RareResources.isCapped(id) && Material.matchMaterial(id) != null;
    }
}
