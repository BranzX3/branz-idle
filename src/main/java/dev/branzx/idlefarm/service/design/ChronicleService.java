package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.GameDesignService.Achievement;
import dev.branzx.idlefarm.service.GameDesignService.Result;
import dev.branzx.idlefarm.storage.GameStateStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Pioneer Chronicle: config-defined achievements and the Chronicle Point
 * wallets (lifetime and seasonal). Definitions live in achievements.yml so
 * content updates never require code changes.
 *
 * <p>An achievement either completes directly through a gameplay event
 * ({@link #complete}) or automatically once every counter listed in its
 * {@code requires} block reaches its threshold. Counters are plain
 * account-scoped numbers fed through {@link #count}/{@link #countMax}, which
 * keeps new tracks purely data-driven.</p>
 */
public final class ChronicleService {

    private record Definition(String id, String category, String name, String description,
                              int points, boolean hidden, long rewardCoins,
                              Map<String, Long> requires) {
    }

    private final IdleFarmPlugin plugin;
    private final GameStateStore state;
    private final AuditService audit;
    private final TelemetryService telemetry;
    private final SeasonService seasons;
    private final CoinSink coins;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    // counter name -> achievements whose requirements reference it
    private final Map<String, List<Definition>> byCounter = new LinkedHashMap<>();

    public ChronicleService(IdleFarmPlugin plugin, GameStateStore state, AuditService audit,
                            TelemetryService telemetry, SeasonService seasons, CoinSink coins) {
        this.plugin = plugin;
        this.state = state;
        this.audit = audit;
        this.telemetry = telemetry;
        this.seasons = seasons;
        this.coins = coins;
    }

    public void loadDefinitions() {
        File file = new File(plugin.getDataFolder(), "achievements.yml");
        if (!file.exists()) {
            plugin.saveResource("achievements.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        try (InputStream bundled = plugin.getResource("achievements.yml")) {
            if (bundled != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(bundled, StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
                yaml.options().copyDefaults(true);
                yaml.save(file);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not merge Chronicle catalog defaults: "
                    + exception.getMessage());
        }
        definitions.clear();
        byCounter.clear();
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            if (section == null) continue;
            Map<String, Long> requires = new LinkedHashMap<>();
            ConfigurationSection requirements = section.getConfigurationSection("requires");
            if (requirements != null) {
                for (String counter : requirements.getKeys(false)) {
                    requires.put(counter, requirements.getLong(counter));
                }
            }
            Definition definition = new Definition(id,
                    section.getString("category", "JOURNEY").toUpperCase(Locale.ROOT),
                    section.getString("name", DesignText.pretty(id)),
                    section.getString("description", ""),
                    Math.max(0, section.getInt("chronicle-points", 1)),
                    section.getBoolean("hidden", false),
                    Math.max(0, section.getLong("reward-coins", 0)),
                    Map.copyOf(requires));
            definitions.put(id, definition);
            for (String counter : requires.keySet()) {
                byCounter.computeIfAbsent(counter, ignored -> new ArrayList<>()).add(definition);
            }
        }
        plugin.getLogger().info("Chronicle: " + definitions.size() + " achievements, "
                + byCounter.size() + " tracked counters.");
    }

    // ---- counters -------------------------------------------------------------

    /** Adds to an account counter and completes any achievement it satisfies. */
    public long count(UUID owner, String counter, long amount) {
        long next = state.increment(owner, "ACCOUNT", "-", counter, amount);
        onCounter(owner, counter);
        return next;
    }

    /** Raises a high-water-mark counter (levels, territory size, team size). */
    public void countMax(UUID owner, String counter, long value) {
        if (value > counter(owner, counter)) {
            state.put(owner, "ACCOUNT", "-", counter, String.valueOf(value));
            onCounter(owner, counter);
        }
    }

    public long counter(UUID owner, String counter) {
        return state.getLong(owner, "ACCOUNT", "-", counter, 0);
    }

    private void onCounter(UUID owner, String counter) {
        List<Definition> candidates = byCounter.get(counter);
        if (candidates == null) return;
        for (Definition definition : candidates) {
            if ("1".equals(state.get(owner, "ACHIEVEMENT", definition.id(), "completed"))) continue;
            boolean satisfied = definition.requires().entrySet().stream()
                    .allMatch(entry -> counter(owner, entry.getKey()) >= entry.getValue());
            if (satisfied) {
                complete(owner, definition.id());
            }
        }
    }

    // ---- queries and claims ---------------------------------------------------

    /** Visible achievements; hidden Feats appear only once earned. */
    public List<Achievement> achievements(UUID owner) {
        return definitions.values().stream()
                .filter(definition -> !definition.hidden()
                        || "1".equals(state.get(owner, "ACHIEVEMENT", definition.id(), "completed")))
                .map(definition -> new Achievement(definition.id(), definition.category(),
                        definition.name(), definition.description(), definition.points(),
                        "1".equals(state.get(owner, "ACHIEVEMENT", definition.id(), "completed")),
                        "1".equals(state.get(owner, "ACHIEVEMENT", definition.id(), "claimed"))))
                .toList();
    }

    public int points(UUID owner) {
        return state.getInt(owner, "ACCOUNT", "-", "chronicle_points", 0);
    }

    public int seasonalPoints(UUID owner) {
        return state.getInt(owner, "SEASON", seasons.id(), "chronicle_points", 0);
    }

    public void addPoints(UUID owner, int points) {
        state.increment(owner, "ACCOUNT", "-", "chronicle_points", points);
    }

    /** Stages a lifetime point gain for a caller-owned transaction. */
    public GameStateStore.Row stagePointsGain(UUID owner, int points) {
        return state.stageIncrement(owner, "ACCOUNT", "-", "chronicle_points", points);
    }

    /** Stages a seasonal point gain for a caller-owned transaction. */
    public GameStateStore.Row stageSeasonalPointsGain(UUID owner, int points) {
        return state.stageIncrement(owner, "SEASON", seasons.id(), "chronicle_points", points);
    }

    public Result claim(UUID owner, String id) {
        Definition definition = definitions.get(id.toLowerCase(Locale.ROOT));
        if (definition == null) return Result.fail("Unknown Chronicle achievement.");
        if (!"1".equals(state.get(owner, "ACHIEVEMENT", definition.id(), "completed"))) {
            return Result.fail("Achievement is not complete.");
        }
        if ("1".equals(state.get(owner, "ACHIEVEMENT", definition.id(), "claimed"))) {
            return Result.fail("Achievement reward already claimed.");
        }
        state.put(owner, "ACHIEVEMENT", definition.id(), "claimed", "1");
        addPoints(owner, definition.points());
        if (definition.rewardCoins() > 0) coins.add(owner, definition.rewardCoins());
        audit.log(owner, "ACHIEVEMENT_CLAIM", "{\"id\":\"" + DesignText.safe(definition.id()) + "\"}");
        String extra = definition.rewardCoins() > 0
                ? " and +" + definition.rewardCoins() + " Coins" : "";
        return Result.ok(definition.name() + " claimed: +" + definition.points()
                + " Chronicle Points" + extra + ".");
    }

    public void complete(UUID owner, String id) {
        if ("1".equals(state.get(owner, "ACHIEVEMENT", id, "completed"))) return;
        state.put(owner, "ACHIEVEMENT", id, "completed", "1");
        telemetry.record(owner, "ACHIEVEMENT_COMPLETED", "{\"id\":\"" + DesignText.safe(id) + "\"}");
    }
}
