package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeRecord;
import dev.branzx.idle.service.AuditService;
import dev.branzx.idle.service.GameDesignService.Commission;
import dev.branzx.idle.service.GameDesignService.Result;
import dev.branzx.idle.service.ProgressionRewards;
import dev.branzx.idle.service.WarehouseService;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.GameStateStore;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Config-driven daily commissions for the Focused Node. Three eligible
 * templates are selected deterministically from owner, date, node family and
 * level bracket, so restarts cannot change a player's board.
 */
public final class CommissionService {

    private enum RewardKind { NODE_EXP, COINS, CHRONICLE_POINTS }

    private record Template(String id, String action, int unlockLevel, int target,
                            int targetPerBracket, String description,
                            RewardKind rewardKind, int rewardAmount) {
        int targetAt(int bracket) {
            return Math.max(1, target + Math.max(0, bracket - 1) * targetPerBracket);
        }
    }

    private static final int DAILY_SLOTS = 3;

    /**
     * Optional hook fired when a commission (or the weekly chapter) crosses
     * its target. Injected so this service stays headless-testable; the
     * plugin wires it to a chat line with a click action.
     */
    @FunctionalInterface
    public interface CompletionNotifier {
        void completed(UUID owner, String message, String actionLabel, String actionCommand);
    }

    private CompletionNotifier completionNotifier = (owner, message, label, command) -> { };

    private final IdlePlugin plugin;
    private final Database database;
    private final GameStateStore state;
    private final AuditService audit;
    private final TelemetryService telemetry;
    private final ProgressionRewards rewards;
    private final FocusService focus;
    private final NodeBuildService builds;
    private final ChronicleService chronicle;
    private final SeasonalChronicleService seasonalChronicle;
    private final WarehouseService warehouse;
    private final NodeExpSink nodeExp;
    private final CoinSink coins;
    private final List<Template> templates = new ArrayList<>();

    public CommissionService(IdlePlugin plugin, Database database, GameStateStore state,
                             AuditService audit, TelemetryService telemetry,
                             ProgressionRewards rewards, FocusService focus,
                             NodeBuildService builds, ChronicleService chronicle,
                             SeasonalChronicleService seasonalChronicle,
                             WarehouseService warehouse, NodeExpSink nodeExp, CoinSink coins) {
        this.plugin = plugin;
        this.database = database;
        this.state = state;
        this.audit = audit;
        this.telemetry = telemetry;
        this.rewards = rewards;
        this.focus = focus;
        this.builds = builds;
        this.chronicle = chronicle;
        this.seasonalChronicle = seasonalChronicle;
        this.warehouse = warehouse;
        this.nodeExp = nodeExp;
        this.coins = coins;
        loadTemplates();
    }

    public void setCompletionNotifier(CompletionNotifier notifier) {
        if (notifier != null) {
            this.completionNotifier = notifier;
        }
    }

    private void loadTemplates() {
        File file = new File(plugin.getDataFolder(), "commissions.yml");
        if (!file.exists()) plugin.saveResource("commissions.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        templates.clear();
        ConfigurationSection root = yaml.getConfigurationSection("templates");
        if (root == null) {
            plugin.getLogger().severe("Commission catalog has no templates section.");
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            try {
                templates.add(new Template(
                        id.toLowerCase(Locale.ROOT),
                        section.getString("action", "produce").toLowerCase(Locale.ROOT),
                        Math.max(1, section.getInt("unlock-level", 3)),
                        Math.max(1, section.getInt("target", 1)),
                        Math.max(0, section.getInt("target-per-bracket", 0)),
                        section.getString("description", DesignText.pretty(id)),
                        RewardKind.valueOf(section.getString("reward.kind", "COINS")
                                .toUpperCase(Locale.ROOT)),
                        Math.max(1, section.getInt("reward.amount", 1))));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid commission template " + id
                        + ": " + exception.getMessage());
            }
        }
        plugin.getLogger().info("Commissions: loaded " + templates.size() + " templates.");
    }

    /** Tracks missed days into simplified catch-up commissions. */
    public void onLogin(UUID owner) {
        String previous = state.get(owner, "ACCOUNT", "-", "last_login_day");
        LocalDate today = GameClock.today();
        if (previous != null) {
            try {
                long missed = java.time.temporal.ChronoUnit.DAYS
                        .between(LocalDate.parse(previous), today) - 1;
                if (missed > 0) {
                    state.put(owner, "ACCOUNT", "-", "catchup_commissions",
                            String.valueOf(Math.min(3, missed)));
                }
            } catch (Exception ignored) {
                // A malformed legacy date should never block login.
            }
        }
        state.put(owner, "ACCOUNT", "-", "last_login_day", today.toString());
    }

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
            exp = extra < rewards.extraExpeditionsPerDay() ? rewards.extraExpeditionExp() : 0;
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
        if (!focus.isFocused(node) || amount <= 0) return;
        UUID owner = node.getOwnerUuid();
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, node, day);
        for (int slot = 1; slot <= DAILY_SLOTS; slot++) {
            Template template = assigned(owner, day, slot);
            if (template != null && template.action().equals(action)) {
                String key = progressKey(slot);
                int target = template.targetAt(bracket(node));
                int current = state.getInt(owner, "DAILY", day, key, 0);
                state.put(owner, "DAILY", day, key,
                        String.valueOf(Math.min(target, current + amount)));
                if (current < target && current + amount >= target) {
                    completionNotifier.completed(owner,
                            "Commission complete: " + template.description(),
                            "[Open Progress]", "/idle progress");
                }
            }
        }
        if (state.claimOnce(owner, "DAILY", day, "weekly_chapter_action")) {
            int weekly = state.getInt(owner, "WEEKLY", GameClock.weekKey(), "chapter_progress", 0);
            state.put(owner, "WEEKLY", GameClock.weekKey(), "chapter_progress",
                    String.valueOf(Math.min(5, weekly + 1)));
            if (weekly == 4) {
                completionNotifier.completed(owner, "Weekly Chapter complete!",
                        "[Claim]", "/idle chapter");
            }
        }
    }

    public List<Commission> commissions(UUID owner) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return List.of();
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, focused, day);
        List<Commission> result = new ArrayList<>();
        for (int slot = 1; slot <= DAILY_SLOTS; slot++) {
            Template template = assigned(owner, day, slot);
            if (template != null) result.add(toCommission(owner, day, focused, slot, template));
        }
        int catchup = state.getInt(owner, "ACCOUNT", "-", "catchup_commissions", 0);
        if (catchup > 0) {
            result.add(new Commission("catchup", "Returning-player simplified commission",
                    1, 1, rewards.catchupCommissionExp() + " Node EXP (catch-up)",
                    "1".equals(state.get(owner, "DAILY", day, "commission_catchup_claimed"))));
        }
        return List.copyOf(result);
    }

    public Result claimCommission(UUID owner, String id) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return Result.fail("Select a Focused Node first.");
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, focused, day);
        if ("catchup".equalsIgnoreCase(id)) return claimCatchup(owner, day, focused);
        int slot = parseSlot(id);
        if (slot < 1 || slot > DAILY_SLOTS) return Result.fail("Unknown commission slot.");
        Template template = assigned(owner, day, slot);
        if (template == null) return Result.fail("Commission template is unavailable.");
        Commission commission = toCommission(owner, day, focused, slot, template);
        if (commission.claimed()) return Result.fail("That commission was already claimed.");
        if (commission.current() < commission.target()) {
            return Result.fail("Commission is not complete yet.");
        }
        if ("delivery".equals(template.action())) {
            Result failure = settleDelivery(owner, day, slot, template, commission.target());
            if (failure != null) return failure;
        } else {
            grantReward(owner, focused, template);
            state.put(owner, "DAILY", day, claimedKey(slot), "1");
        }
        audit.log(owner, "COMMISSION_CLAIM", "{\"slot\":" + slot + ",\"template\":\""
                + DesignText.safe(template.id()) + "\",\"day\":\"" + day + "\"}");
        telemetry.record(owner, "COMMISSION_CLAIM",
                "{\"template\":\"" + DesignText.safe(template.id()) + "\"}");
        chronicle.count(owner, "commissions_claimed", 1);
        seasonalChronicle.advance(owner, "commission", 1);
        return Result.ok("Commission reward claimed.");
    }

    /** Replaces one unclaimed slot. Exactly one reroll is free each game day. */
    public Result reroll(UUID owner, String id) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return Result.fail("Select a Focused Node first.");
        String day = GameClock.dayKey();
        ensureDailyCommissions(owner, focused, day);
        if ("1".equals(state.get(owner, "DAILY", day, "commission_reroll_used"))) {
            return Result.fail("Today's free commission reroll was already used.");
        }
        int slot = parseSlot(id);
        if (slot < 1 || slot > DAILY_SLOTS) return Result.fail("Choose slot_1, slot_2, or slot_3.");
        if ("1".equals(state.get(owner, "DAILY", day, claimedKey(slot)))) {
            return Result.fail("A claimed commission cannot be rerolled.");
        }
        Set<String> selected = new LinkedHashSet<>();
        for (int index = 1; index <= DAILY_SLOTS; index++) {
            if (index != slot) {
                Template current = assigned(owner, day, index);
                if (current != null) selected.add(current.id());
            }
        }
        List<Template> candidates = eligible(focused).stream()
                .filter(template -> !selected.contains(template.id()))
                .filter(template -> {
                    Template old = assigned(owner, day, slot);
                    return old == null || !old.id().equals(template.id());
                }).toList();
        if (candidates.isEmpty()) return Result.fail("No alternative commission is available.");
        long seed = seed(owner, day, focused) ^ (slot * 31L) ^ 0x5DEECE66DL;
        Template replacement = candidates.get(new Random(seed).nextInt(candidates.size()));
        state.put(owner, "DAILY", day, assignmentKey(slot), replacement.id());
        state.put(owner, "DAILY", day, progressKey(slot), "0");
        state.put(owner, "DAILY", day, "commission_reroll_used", "1");
        audit.log(owner, "COMMISSION_REROLL", "{\"slot\":" + slot + ",\"template\":\""
                + DesignText.safe(replacement.id()) + "\"}");
        return Result.ok("Commission rerolled to " + replacement.description() + ".");
    }

    private Result claimCatchup(UUID owner, String day, NodeRecord focused) {
        if ("1".equals(state.get(owner, "DAILY", day, "commission_catchup_claimed"))) {
            return Result.fail("That commission was already claimed.");
        }
        int available = state.getInt(owner, "ACCOUNT", "-", "catchup_commissions", 0);
        if (available <= 0) return Result.fail("No catch-up commission is available.");
        nodeExp.grant(focused, rewards.catchupCommissionExp());
        state.put(owner, "ACCOUNT", "-", "catchup_commissions", String.valueOf(available - 1));
        state.put(owner, "DAILY", day, "commission_catchup_claimed", "1");
        telemetry.record(owner, "CATCH_UP_COMMISSION_CLAIMED",
                "{\"remaining\":" + (available - 1) + "}");
        return Result.ok("Catch-up commission claimed.");
    }

    private Result settleDelivery(UUID owner, String day, int slot,
                                  Template template, int target) {
        Map<String, Integer> contents = warehouse.getContents(owner);
        int available = supplyTotal(contents);
        if (available < target) {
            return Result.fail("Warehouse no longer has the required resources.");
        }
        WarehouseService.Snapshot before = warehouse.snapshot(owner);
        int remaining = target;
        for (Map.Entry<String, Integer> entry : contents.entrySet()) {
            if (remaining <= 0) break;
            if (!isCommissionSupply(entry.getKey())) continue;
            remaining -= warehouse.prepareWithdraw(owner, entry.getKey(),
                    Math.min(remaining, entry.getValue()));
        }
        WarehouseService.Snapshot snapshot = warehouse.snapshot(owner);
        List<GameStateStore.Row> rows = new ArrayList<>();
        rows.add(state.prepare(owner, "DAILY", day, claimedKey(slot), "1"));
        switch (template.rewardKind()) {
            case CHRONICLE_POINTS ->
                    rows.add(chronicle.stagePointsGain(owner, template.rewardAmount()));
            case NODE_EXP, COINS -> {
                // These reward kinds use their authoritative aggregate sink
                // after the Warehouse transaction has been accepted.
            }
        }
        boolean committed = database.executeTransaction("delivery commission " + owner, connection -> {
            warehouse.write(connection, before, snapshot);
            for (GameStateStore.Row row : rows) GameStateStore.write(connection, row);
        });
        if (!committed) {
            warehouse.restore(before);
            return Result.fail("Commission settlement failed; no resources were consumed.");
        }
        rows.forEach(state::applyCommitted);
        if (template.rewardKind() != RewardKind.CHRONICLE_POINTS) {
            NodeRecord focused = focus.focusedRecord(owner);
            if (focused != null) grantReward(owner, focused, template);
        }
        return null;
    }

    public Result claimWeeklyChapter(UUID owner) {
        NodeRecord focused = focus.focusedRecord(owner);
        if (focused == null) return Result.fail("Select a Focused Node first.");
        String week = GameClock.weekKey();
        int progress = state.getInt(owner, "WEEKLY", week, "chapter_progress", 0);
        if (progress < 5) {
            return Result.fail("Weekly Chapter needs 5 daily actions (" + progress + "/5).");
        }
        if (!state.claimOnce(owner, "WEEKLY", week, "chapter_claimed")) {
            return Result.fail("Weekly Chapter already claimed.");
        }
        nodeExp.grant(focused, rewards.weeklyChapterExp());
        coins.add(owner, rewards.weeklyChapterCoins());
        chronicle.count(owner, "weekly_chapters", 1);
        audit.log(owner, "CHAPTER_CLAIM", "{\"week\":\"" + week + "\"}");
        return Result.ok("Weekly Node Chapter claimed: +" + rewards.weeklyChapterExp()
                + " Node EXP and +" + rewards.weeklyChapterCoins() + " Coins.");
    }

    private void ensureDailyCommissions(UUID owner, NodeRecord focused, String day) {
        if (state.get(owner, "DAILY", day, assignmentKey(1)) != null) return;
        List<Template> eligible = new ArrayList<>(eligible(focused));
        eligible.sort(Comparator.comparing(Template::id));
        java.util.Collections.shuffle(eligible, new Random(seed(owner, day, focused)));
        if (eligible.size() < DAILY_SLOTS) {
            plugin.getLogger().warning("Only " + eligible.size()
                    + " commission templates are eligible at level "
                    + focused.getExplorationLevel() + ".");
        }
        for (int slot = 1; slot <= Math.min(DAILY_SLOTS, eligible.size()); slot++) {
            state.put(owner, "DAILY", day, assignmentKey(slot), eligible.get(slot - 1).id());
            state.put(owner, "DAILY", day, progressKey(slot), "0");
        }
        state.put(owner, "DAILY", day, "commission_seed",
                focused.getType().name() + ":" + bracket(focused));
    }

    private List<Template> eligible(NodeRecord node) {
        return templates.stream()
                .filter(template -> node.getExplorationLevel() >= template.unlockLevel())
                .toList();
    }

    private Commission toCommission(UUID owner, String day, NodeRecord node,
                                    int slot, Template template) {
        int target = template.targetAt(bracket(node));
        int progress = "delivery".equals(template.action())
                ? Math.min(target, supplyTotal(warehouse.getContents(owner)))
                : state.getInt(owner, "DAILY", day, progressKey(slot), 0);
        return new Commission("slot_" + slot,
                template.description().replace("{node}", DesignText.pretty(node.getType().name())),
                progress, target, rewardText(template),
                "1".equals(state.get(owner, "DAILY", day, claimedKey(slot))));
    }

    private void grantReward(UUID owner, NodeRecord focused, Template template) {
        switch (template.rewardKind()) {
            case NODE_EXP -> nodeExp.grant(focused, template.rewardAmount());
            case COINS -> coins.add(owner, template.rewardAmount());
            case CHRONICLE_POINTS -> chronicle.addPoints(owner, template.rewardAmount());
        }
    }

    private String rewardText(Template template) {
        return switch (template.rewardKind()) {
            case NODE_EXP -> template.rewardAmount() + " Node EXP";
            case COINS -> template.rewardAmount() + " Coins";
            case CHRONICLE_POINTS -> template.rewardAmount() + " Chronicle Points";
        };
    }

    private Template assigned(UUID owner, String day, int slot) {
        String id = state.get(owner, "DAILY", day, assignmentKey(slot));
        if (id == null) return null;
        return templates.stream().filter(template -> template.id().equals(id)).findFirst().orElse(null);
    }

    private int parseSlot(String id) {
        if (id == null || !id.toLowerCase(Locale.ROOT).startsWith("slot_")) return -1;
        try {
            return Integer.parseInt(id.substring(5));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int bracket(NodeRecord node) {
        return Math.max(1, Math.min(10, node.getExplorationLevel() / 10 + 1));
    }

    private long seed(UUID owner, String day, NodeRecord node) {
        return owner.getMostSignificantBits() ^ owner.getLeastSignificantBits()
                ^ day.hashCode() ^ ((long) node.getType().ordinal() << 32) ^ bracket(node);
    }

    private String assignmentKey(int slot) { return "commission_slot_" + slot; }
    private String progressKey(int slot) { return "commission_slot_" + slot + "_progress"; }
    private String claimedKey(int slot) { return "commission_slot_" + slot + "_claimed"; }

    private int supplyTotal(Map<String, Integer> contents) {
        return contents.entrySet().stream().filter(entry -> isCommissionSupply(entry.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
    }

    private boolean isCommissionSupply(String material) {
        String id = material.toUpperCase(Locale.ROOT);
        return !RareResources.isCapped(id) && Material.matchMaterial(id) != null;
    }
}
