package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.service.GameDesignService.Result;
import dev.branzx.idle.storage.GameStateStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Seasonal objectives and cosmetic reward-track entitlements. */
public final class SeasonalChronicleService {

    private record Definition(String id, int week, String name, String description,
                              String action, int target, int points, String reward) {
    }

    private record TierDefinition(String id, int points, String name, String reward) {
    }

    public record Objective(String id, int week, String name, String description,
                            int current, int target, int points, String reward,
                            boolean completed, boolean claimed, boolean catchUp) {
    }

    public record RewardTier(String id, int points, String name, String reward,
                             boolean unlocked, boolean claimed) {
    }

    /**
     * One finished season as the permanent Chronicle remembers it. Seasonal
     * points and entitlements stay scoped to their own season; this is the
     * lifetime record that a new season cannot overwrite.
     */
    public record Participation(String seasonId, int points, int objectives, int rewardTiers) {
    }

    /**
     * Optional hook fired when a finished season is archived on login.
     * Injected so the service stays headless-testable; the plugin wires it to
     * a chat line with a Chronicle link.
     */
    @FunctionalInterface
    public interface ArchiveNotifier {
        void archived(UUID owner, Participation participation);
    }

    private static final String ARCHIVE_SCOPE = "SEASON_ARCHIVE";
    private static final String LAST_SEEN = "season_last_seen";

    private ArchiveNotifier archiveNotifier = (owner, participation) -> { };

    private final IdlePlugin plugin;
    private final GameStateStore state;
    private final SeasonService seasons;
    private final FeatureControlService controls;
    private final ChronicleService chronicle;
    private final TelemetryService telemetry;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final List<TierDefinition> tiers = new ArrayList<>();

    public SeasonalChronicleService(IdlePlugin plugin, GameStateStore state,
                                    SeasonService seasons, FeatureControlService controls,
                                    ChronicleService chronicle, TelemetryService telemetry) {
        this.plugin = plugin;
        this.state = state;
        this.seasons = seasons;
        this.controls = controls;
        this.chronicle = chronicle;
        this.telemetry = telemetry;
    }

    public void loadDefinitions() {
        File file = new File(plugin.getDataFolder(), "seasonal-objectives.yml");
        if (!file.exists()) plugin.saveResource("seasonal-objectives.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = plugin.getResource("seasonal-objectives.yml")) {
            if (bundled != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
                yaml.options().copyDefaults(true);
                yaml.save(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not merge seasonal objective defaults: "
                    + exception.getMessage());
        }
        definitions.clear();
        ConfigurationSection objectives = yaml.getConfigurationSection("objectives");
        if (objectives != null) {
            for (String id : objectives.getKeys(false)) {
                ConfigurationSection section = objectives.getConfigurationSection(id);
                if (section == null) continue;
                int week = section.getInt("week", 1);
                int target = section.getInt("target", 1);
                int points = section.getInt("points", 0);
                String action = section.getString("action", "").toLowerCase(Locale.ROOT);
                if (week < 1 || week > seasons.durationWeeks() || target < 1
                        || points < 0 || action.isBlank()) {
                    plugin.getLogger().warning("Skipping invalid seasonal objective " + id);
                    continue;
                }
                Definition definition = new Definition(id.toLowerCase(Locale.ROOT), week,
                        section.getString("name", DesignText.pretty(id)),
                        section.getString("description", ""), action, target, points,
                        section.getString("reward", ""));
                definitions.put(definition.id(), definition);
            }
        }
        tiers.clear();
        ConfigurationSection rewards = yaml.getConfigurationSection("reward-track");
        if (rewards != null) {
            for (String id : rewards.getKeys(false)) {
                ConfigurationSection section = rewards.getConfigurationSection(id);
                if (section == null || section.getInt("points", -1) < 0) continue;
                tiers.add(new TierDefinition(id.toLowerCase(Locale.ROOT),
                        section.getInt("points"), section.getString("name", DesignText.pretty(id)),
                        section.getString("reward", "")));
            }
        }
        tiers.sort(Comparator.comparingInt(TierDefinition::points));
        plugin.getLogger().info("Seasonal Chronicle: " + definitions.size()
                + " objectives and " + tiers.size() + " reward tiers.");
    }

    public void setArchiveNotifier(ArchiveNotifier notifier) {
        if (notifier != null) this.archiveNotifier = notifier;
    }

    /**
     * Closes the season the player last played in, if the operator has since
     * started a new one. Seasonal points, objectives and entitlements are
     * scoped by season id, so a rollover would otherwise leave the previous
     * season invisible: the rows survive but nothing reads them again. This
     * writes the permanent record the Chronicle shows instead.
     *
     * <p>Runs on login rather than on a season-change event because the season
     * is a config value: it can change while the player is offline, or while
     * the server is down.</p>
     */
    public void archiveFinishedSeason(UUID owner) {
        String current = seasons.id();
        String previous = state.get(owner, "ACCOUNT", "-", LAST_SEEN);
        if (previous == null || previous.isBlank()) {
            state.put(owner, "ACCOUNT", "-", LAST_SEEN, current);
            return;
        }
        if (previous.equals(current)) return;
        Participation participation = snapshot(owner, previous);
        state.put(owner, "ACCOUNT", "-", LAST_SEEN, current);
        if (participation.points() == 0 && participation.objectives() == 0
                && participation.rewardTiers() == 0) {
            // Nothing happened that season; a blank page is not a record.
            return;
        }
        if (!state.claimOnce(owner, ARCHIVE_SCOPE, previous, "archived")) return;
        state.put(owner, ARCHIVE_SCOPE, previous, "points",
                String.valueOf(participation.points()));
        state.put(owner, ARCHIVE_SCOPE, previous, "objectives",
                String.valueOf(participation.objectives()));
        state.put(owner, ARCHIVE_SCOPE, previous, "reward_tiers",
                String.valueOf(participation.rewardTiers()));
        chronicle.count(owner, "seasons_participated", 1);
        telemetry.record(owner, "SEASON_ARCHIVED",
                "{\"season\":\"" + DesignText.safe(previous) + "\",\"points\":"
                        + participation.points() + ",\"objectives\":" + participation.objectives()
                        + ",\"tiers\":" + participation.rewardTiers() + "}");
        archiveNotifier.archived(owner, participation);
    }

    /** Seasons this player finished, oldest archive key first. */
    public List<Participation> participation(UUID owner) {
        return state.scopeIds(owner, ARCHIVE_SCOPE).stream()
                .map(seasonId -> new Participation(seasonId,
                        state.getInt(owner, ARCHIVE_SCOPE, seasonId, "points", 0),
                        state.getInt(owner, ARCHIVE_SCOPE, seasonId, "objectives", 0),
                        state.getInt(owner, ARCHIVE_SCOPE, seasonId, "reward_tiers", 0)))
                .toList();
    }

    /**
     * Counts a finished season from its own durable rows rather than from the
     * current catalog: the objective and reward-track definitions may already
     * have been replaced for the new season.
     */
    private Participation snapshot(UUID owner, String seasonId) {
        String prefix = seasonId + ":";
        int objectives = (int) state.scopeIds(owner, "SEASON_OBJECTIVE").stream()
                .filter(scope -> scope.startsWith(prefix))
                .filter(scope -> "1".equals(state.get(owner, "SEASON_OBJECTIVE", scope, "completed")))
                .count();
        int tiers = (int) state.scopeIds(owner, "SEASON_REWARD").stream()
                .filter(scope -> scope.startsWith(prefix))
                .filter(scope -> "1".equals(state.get(owner, "SEASON_REWARD", scope, "claimed")))
                .count();
        return new Participation(seasonId,
                state.getInt(owner, "SEASON", seasonId, "chronicle_points", 0), objectives, tiers);
    }

    public void advance(UUID owner, String action, int amount) {
        if (amount <= 0 || !controls.enabled("seasonal-chronicle", owner)) return;
        String normalized = action.toLowerCase(Locale.ROOT);
        int week = seasons.week();
        List<Definition> current = matching(normalized, week, week);
        current.forEach(definition -> advanceOne(owner, definition, amount));
        if (!plugin.getConfig().getBoolean("live-ops.catch-up.weekly-objectives", true)) return;
        definitions.values().stream()
                .filter(definition -> definition.week() < week && definition.action().equals(normalized))
                .filter(definition -> !completed(owner, definition))
                .min(Comparator.comparingInt(Definition::week).thenComparing(Definition::id))
                .ifPresent(definition -> advanceOne(owner, definition, amount));
    }

    private List<Definition> matching(String action, int minimumWeek, int maximumWeek) {
        return definitions.values().stream()
                .filter(definition -> definition.week() >= minimumWeek
                        && definition.week() <= maximumWeek && definition.action().equals(action))
                .toList();
    }

    private void advanceOne(UUID owner, Definition definition, int amount) {
        String scope = scope(definition.id());
        int current = state.getInt(owner, "SEASON_OBJECTIVE", scope, "progress", 0);
        if (current >= definition.target()) return;
        int next = Math.min(definition.target(), current + amount);
        state.put(owner, "SEASON_OBJECTIVE", scope, "progress", String.valueOf(next));
        if (next >= definition.target()
                && state.claimOnce(owner, "SEASON_OBJECTIVE", scope, "completed")) {
            telemetry.record(owner, "SEASON_OBJECTIVE_COMPLETED",
                    "{\"season\":\"" + DesignText.safe(seasons.id()) + "\",\"id\":\""
                            + DesignText.safe(definition.id()) + "\",\"week\":" + definition.week() + "}");
        }
    }

    public List<Objective> objectives(UUID owner) {
        if (!controls.enabled("seasonal-chronicle", owner)) return List.of();
        int currentWeek = seasons.week();
        return definitions.values().stream()
                .filter(definition -> definition.week() <= currentWeek)
                .sorted(Comparator.comparingInt(Definition::week).thenComparing(Definition::id))
                .map(definition -> {
                    String scope = scope(definition.id());
                    int progress = Math.min(definition.target(),
                            state.getInt(owner, "SEASON_OBJECTIVE", scope, "progress", 0));
                    return new Objective(definition.id(), definition.week(), definition.name(),
                            definition.description(), progress, definition.target(),
                            definition.points(), definition.reward(),
                            completed(owner, definition),
                            "1".equals(state.get(owner, "SEASON_OBJECTIVE", scope, "claimed")),
                            definition.week() < currentWeek);
                }).toList();
    }

    public Result claim(UUID owner, String id) {
        Definition definition = definitions.get(id.toLowerCase(Locale.ROOT));
        if (definition == null || definition.week() > seasons.week()) {
            return Result.fail("Unknown or locked seasonal objective.");
        }
        String scope = scope(definition.id());
        if (!completed(owner, definition)) return Result.fail("Seasonal objective is incomplete.");
        if (!state.claimOnce(owner, "SEASON_OBJECTIVE", scope, "claimed")) {
            return Result.fail("Seasonal objective reward already claimed.");
        }
        if (definition.points() > 0) {
            state.increment(owner, "SEASON", seasons.id(), "chronicle_points", definition.points());
        }
        if (!definition.reward().isBlank()) {
            state.put(owner, "SEASON_ENTITLEMENT", seasons.id(),
                    "objective_" + definition.id(), definition.reward());
        }
        telemetry.record(owner, "SEASON_OBJECTIVE_CLAIMED",
                "{\"season\":\"" + DesignText.safe(seasons.id()) + "\",\"id\":\""
                        + DesignText.safe(definition.id()) + "\",\"points\":" + definition.points() + "}");
        return Result.ok(definition.name() + " claimed: +" + definition.points()
                + " Seasonal Chronicle Points.");
    }

    public List<RewardTier> rewardTrack(UUID owner) {
        int points = chronicle.seasonalPoints(owner);
        return tiers.stream().map(tier -> new RewardTier(tier.id(), tier.points(), tier.name(),
                tier.reward(), points >= tier.points(),
                "1".equals(state.get(owner, "SEASON_REWARD", scope(tier.id()), "claimed"))))
                .toList();
    }

    public Result claimReward(UUID owner, String id) {
        TierDefinition tier = tiers.stream().filter(value -> value.id().equalsIgnoreCase(id))
                .findFirst().orElse(null);
        if (tier == null) return Result.fail("Unknown seasonal reward tier.");
        if (chronicle.seasonalPoints(owner) < tier.points()) {
            return Result.fail("Not enough Seasonal Chronicle Points.");
        }
        if (!state.claimOnce(owner, "SEASON_REWARD", scope(tier.id()), "claimed")) {
            return Result.fail("Seasonal reward already claimed.");
        }
        state.put(owner, "SEASON_ENTITLEMENT", seasons.id(), "tier_" + tier.id(), tier.reward());
        telemetry.record(owner, "SEASON_REWARD_CLAIMED",
                "{\"season\":\"" + DesignText.safe(seasons.id()) + "\",\"tier\":\""
                        + DesignText.safe(tier.id()) + "\"}");
        return Result.ok(tier.name() + " unlocked: " + tier.reward() + ".");
    }

    private boolean completed(UUID owner, Definition definition) {
        return "1".equals(state.get(owner, "SEASON_OBJECTIVE", scope(definition.id()), "completed"))
                || state.getInt(owner, "SEASON_OBJECTIVE", scope(definition.id()), "progress", 0)
                >= definition.target();
    }

    private String scope(String id) {
        return seasons.id() + ":" + id;
    }
}
