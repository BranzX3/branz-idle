package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.GameDesignService.Achievement;
import dev.branzx.idlefarm.service.GameDesignService.Result;
import dev.branzx.idlefarm.storage.GameStateStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Chronicle: config-defined achievements and the Chronicle Point wallets
 * (lifetime and seasonal). Definitions live in achievements.yml so content
 * updates never require code changes.
 */
public final class ChronicleService {

    private record Definition(String id, String name, String description, int points) {
    }

    private final IdleFarmPlugin plugin;
    private final GameStateStore state;
    private final AuditService audit;
    private final TelemetryService telemetry;
    private final SeasonService seasons;
    private final CoinSink coins;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();

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
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(id);
            if (section == null) continue;
            definitions.put(id, new Definition(id,
                    section.getString("name", DesignText.pretty(id)),
                    section.getString("description", ""),
                    Math.max(0, section.getInt("chronicle-points", 1))));
        }
    }

    public List<Achievement> achievements(UUID owner) {
        return definitions.values().stream()
                .map(definition -> new Achievement(definition.id(), definition.name(),
                        definition.description(), definition.points(),
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
        Achievement achievement = achievements(owner).stream()
                .filter(a -> a.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (achievement == null) return Result.fail("Unknown Chronicle achievement.");
        if (!achievement.completed()) return Result.fail("Achievement is not complete.");
        if (achievement.claimed()) return Result.fail("Achievement reward already claimed.");
        state.put(owner, "ACHIEVEMENT", achievement.id(), "claimed", "1");
        addPoints(owner, achievement.points());
        if ("supplies_arrive".equals(achievement.id())) coins.add(owner, 250);
        audit.log(owner, "ACHIEVEMENT_CLAIM", "{\"id\":\"" + DesignText.safe(achievement.id()) + "\"}");
        return Result.ok(achievement.name() + " claimed: +" + achievement.points() + " Chronicle Points.");
    }

    public void complete(UUID owner, String id) {
        if ("1".equals(state.get(owner, "ACHIEVEMENT", id, "completed"))) return;
        state.put(owner, "ACHIEVEMENT", id, "completed", "1");
        telemetry.record(owner, "ACHIEVEMENT_COMPLETED", "{\"id\":\"" + DesignText.safe(id) + "\"}");
    }
}
