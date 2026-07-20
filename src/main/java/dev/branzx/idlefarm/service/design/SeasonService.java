package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;

import java.time.LocalDate;

/** Config-driven twelve-week season calendar and its weekly modifier. */
public final class SeasonService {

    private final IdleFarmPlugin plugin;

    public SeasonService(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    public String id() {
        return plugin.getConfig().getString("season.id", "preseason");
    }

    public int week() {
        String startText = plugin.getConfig().getString("season.start-date", GameClock.dayKey());
        try {
            LocalDate start = LocalDate.parse(startText);
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, GameClock.today());
            return (int) Math.max(1, Math.min(12, days / 7 + 1));
        } catch (Exception ignored) {
            return 1;
        }
    }

    public String phase() {
        int week = week();
        if (week <= 2) return "Discovery";
        if (week <= 5) return "Development";
        if (week == 6) return "Midseason";
        if (week <= 9) return "Mastery";
        if (week <= 11) return "Finale";
        return "Celebration";
    }

    public String modifier() {
        return switch ((week() - 1) % 5) {
            case 0 -> "LONG_ROUTES";
            case 1 -> "UNSTABLE_VEINS";
            case 2 -> "RESEARCH_WEEK";
            case 3 -> "MIXED_CREWS";
            default -> "SUPPLY_SHORTAGE";
        };
    }
}
