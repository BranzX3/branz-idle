package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Config-driven season calendar, weekly cadence and event modifiers. */
public final class SeasonService {

    public record WeekSchedule(int week, String phase, String modifier,
                               String eventFamily, String bonusWindow) {
    }

    private final IdlePlugin plugin;

    public SeasonService(IdlePlugin plugin) {
        this.plugin = plugin;
    }

    public String id() {
        return plugin.getConfig().getString("season.id", "preseason");
    }

    public int durationWeeks() {
        return Math.max(1, Math.min(52,
                plugin.getConfig().getInt("season.duration-weeks", 12)));
    }

    public int week() {
        String startText = plugin.getConfig().getString("season.start-date", GameClock.dayKey());
        try {
            LocalDate start = LocalDate.parse(startText);
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, GameClock.today());
            return (int) Math.max(1, Math.min(durationWeeks(), days / 7 + 1));
        } catch (Exception ignored) {
            return 1;
        }
    }

    public String phase() {
        return schedule().phase();
    }

    public String modifier() {
        return schedule().modifier();
    }

    public WeekSchedule schedule() {
        int week = week();
        ConfigurationSection configured =
                plugin.getConfig().getConfigurationSection("season.weeks." + week);
        String fallbackPhase = defaultPhase(week);
        String fallbackModifier = rotationModifier(week);
        if (configured == null) {
            return new WeekSchedule(week, fallbackPhase, fallbackModifier, "STANDARD", "");
        }
        return new WeekSchedule(week,
                configured.getString("phase", fallbackPhase),
                normalize(configured.getString("modifier", fallbackModifier), fallbackModifier),
                normalize(configured.getString("event-family", "STANDARD"), "STANDARD"),
                configured.getString("bonus-window", ""));
    }

    public List<String> validationErrors() {
        List<String> errors = new ArrayList<>();
        try {
            LocalDate.parse(plugin.getConfig().getString("season.start-date", ""));
        } catch (Exception exception) {
            errors.add("season.start-date must use yyyy-MM-dd");
        }
        ConfigurationSection weeks = plugin.getConfig().getConfigurationSection("season.weeks");
        if (weeks != null) {
            for (String key : weeks.getKeys(false)) {
                int week;
                try {
                    week = Integer.parseInt(key);
                } catch (NumberFormatException exception) {
                    errors.add("season.weeks." + key + " is not a week number");
                    continue;
                }
                if (week < 1 || week > durationWeeks()) {
                    errors.add("season.weeks." + key + " is outside the season");
                }
                String modifier = weeks.getString(key + ".modifier", "");
                if (modifier.isBlank()) errors.add("season.weeks." + key + ".modifier is required");
            }
        }
        return List.copyOf(errors);
    }

    private String defaultPhase(int week) {
        if (week <= 2) return "Discovery";
        if (week <= 5) return "Development";
        if (week == 6) return "Midseason";
        if (week <= 9) return "Mastery";
        if (week <= 11) return "Finale";
        return "Celebration";
    }

    private String rotationModifier(int week) {
        List<String> rotation = plugin.getConfig().getStringList("season.modifier-rotation");
        if (rotation.isEmpty()) {
            rotation = List.of("LONG_ROUTES", "UNSTABLE_VEINS", "RESEARCH_WEEK",
                    "MIXED_CREWS", "SUPPLY_SHORTAGE");
        }
        return normalize(rotation.get((week - 1) % rotation.size()), "LONG_ROUTES");
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
