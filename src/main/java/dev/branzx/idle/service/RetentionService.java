package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import dev.branzx.wallet.event.CommunityNotification;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Retention nudges. Once a day, at a configured hour, it DMs players whose login
 * streak is alive but who have not logged in yet today — "log in or lose your
 * streak". Delivery reuses the shared {@link CommunityNotification} (dm = true);
 * players without a linked Discord are silently skipped by the Discord
 * front-end, so this never spams, and the once-per-day guard means each at-risk
 * player is nudged at most once.
 */
public final class RetentionService {

    private record Reminder(UUID uuid, String name, int days) {
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");

    private final IdlePlugin plugin;
    private final Database database;
    private volatile String lastRunDay = "";

    public RetentionService(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void start() {
        // Check hourly; the target-hour + last-run-day guards fire it once a day.
        long hourly = 20L * 60L * 60L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L * 60L, hourly);
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("retention.streak-reminder.enabled", true)) {
            return;
        }
        int targetHour = plugin.getConfig().getInt("retention.streak-reminder.hour", 20);
        int minStreak = plugin.getConfig().getInt("retention.streak-reminder.min-streak", 2);
        LocalDate today = LocalDate.now(ZONE);
        if (LocalTime.now(ZONE).getHour() != targetHour || today.toString().equals(lastRunDay)) {
            return;
        }
        lastRunDay = today.toString();
        String yesterday = today.minusDays(1).toString();

        List<Reminder> due = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT s.owner_uuid, s.current_streak, p.name FROM idle_streaks s "
                             + "LEFT JOIN idle_players p ON s.owner_uuid = p.uuid "
                             + "WHERE s.current_streak >= ? AND s.last_login_day = ?")) {
            select.setInt(1, minStreak);
            select.setString(2, yesterday);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    due.add(new Reminder(UUID.fromString(rs.getString("owner_uuid")),
                            rs.getString("name"), rs.getInt("current_streak")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to query streak reminders: " + e.getMessage());
            return;
        }
        if (due.isEmpty()) {
            return;
        }
        // CommunityNotification is a synchronous Bukkit event — fire on the main thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Reminder r : due) {
                Bukkit.getPluginManager().callEvent(new CommunityNotification(
                        CommunityNotification.Kind.STREAK, r.uuid(),
                        r.name() == null ? "ผู้เล่น" : r.name(),
                        "⏰ อย่าให้ streak ขาด!",
                        "streak " + r.days() + " วันของคุณจะขาดถ้าไม่ล็อกอินวันนี้ — "
                                + "รีบเข้ามารับโบนัสก่อนหมดวัน!",
                        false, true));
            }
            plugin.getLogger().info("Sent " + due.size() + " streak reminder(s).");
        });
    }
}
