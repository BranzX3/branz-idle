package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily login streak: consecutive days grant an escalating Money bonus
 * (base × streak, capped). Missing a day resets to 1.
 */
public final class StreakService {

    private record Streak(int days, String lastDay) {
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final PlayerDataStore dataStore;
    private final Map<UUID, Streak> streaks = new ConcurrentHashMap<>();

    public StreakService(IdleFarmPlugin plugin, Database database, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.database = database;
        this.dataStore = dataStore;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, current_streak, last_login_day FROM idlefarm_streaks");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                streaks.put(UUID.fromString(rs.getString("owner_uuid")),
                        new Streak(rs.getInt("current_streak"), rs.getString("last_login_day")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load streaks: " + e.getMessage());
        }
    }

    public int currentStreak(UUID owner) {
        Streak streak = streaks.get(owner);
        return streak == null ? 0 : streak.days();
    }

    /** Call on join (after player data loads). Pays the daily bonus once per day. */
    public void handleLogin(Player player) {
        UUID owner = player.getUniqueId();
        String today = LocalDate.now().toString();
        Streak current = streaks.get(owner);
        if (current != null && today.equals(current.lastDay())) {
            return; // already counted today
        }
        int days;
        if (current != null && LocalDate.parse(current.lastDay()).plusDays(1).toString().equals(today)) {
            days = current.days() + 1;
        } else {
            days = 1;
        }
        Streak updated = new Streak(days, today);
        streaks.put(owner, updated);
        persist(owner, updated);

        double base = plugin.getConfig().getDouble("streak.base-money", 100);
        int capDays = plugin.getConfig().getInt("streak.max-multiplier-days", 7);
        double bonus = base * Math.min(days, capDays);
        PlayerData data = dataStore.getOnline(owner);
        if (data != null) {
            data.addBalance(bonus);
        }
        String currency = plugin.getConfig().getString("currency-name", "Coins");
        player.sendMessage(Component.text()
                .append(Component.text("[Streak] ", NamedTextColor.GOLD))
                .append(Component.text("Day " + days + "! +" + (long) bonus + " " + currency,
                        NamedTextColor.GREEN))
                .build());
    }

    private void persist(UUID owner, Streak streak) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_streaks (owner_uuid, current_streak, last_login_day) "
                                 + "VALUES (?, ?, ?)")) {
                upsert.setString(1, owner.toString());
                upsert.setInt(2, streak.days());
                upsert.setString(3, streak.lastDay());
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist streak: " + e.getMessage());
            }
        });
    }
}
