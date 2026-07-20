package dev.branzx.idlefarm.service.design;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;

/**
 * Daily and weekly gameplay boundaries share one game timezone. System-default
 * time must never decide commissions, caps, streaks, seasons or settlement.
 */
public final class GameClock {

    public static final ZoneId GAME_ZONE = ZoneId.of("Asia/Bangkok");

    private GameClock() {
    }

    public static LocalDate today() {
        return LocalDate.now(GAME_ZONE);
    }

    public static String dayKey() {
        return today().toString();
    }

    public static String weekKey() {
        LocalDate now = today();
        WeekFields fields = WeekFields.of(DayOfWeek.MONDAY, 4);
        return now.get(fields.weekBasedYear()) + "-W"
                + String.format("%02d", now.get(fields.weekOfWeekBasedYear()));
    }
}
