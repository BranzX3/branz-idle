package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import dev.branzx.wallet.event.CommunityNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily login streak: consecutive days grant an escalating Money bonus
 * (base × streak, capped). Missing a day resets to 1.
 */
public final class StreakService {

    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Bangkok");

    private record Streak(int days, String lastDay) {
    }

    private final IdlePlugin plugin;
    private final Database database;
    private final PlayerDataStore dataStore;
    private final Map<UUID, Streak> streaks = new ConcurrentHashMap<>();

    public StreakService(IdlePlugin plugin, Database database, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.database = database;
        this.dataStore = dataStore;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, current_streak, last_login_day FROM idle_streaks");
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
        // The daily bonus is granted by the server that hosts the simulation.
        // A remote server's streak cache is a boot-time snapshot, so letting it
        // decide would risk paying the same day twice.
        if (plugin.isRemote()) {
            return;
        }
        UUID owner = player.getUniqueId();
        String today = LocalDate.now(GAME_ZONE).toString();
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
        double base = plugin.getConfig().getDouble("streak.base-money", 100);
        int capDays = plugin.getConfig().getInt("streak.max-multiplier-days", 7);
        double bonus = base * Math.min(days, capDays);
        PlayerData data = dataStore.getOnline(owner);
        if (data == null) return;
        Streak updated = new Streak(days, today);
        boolean committed = database.executeTransaction("daily streak " + owner + " " + today,
                connection -> {
                    try (PreparedStatement upsert = connection.prepareStatement(
                            "REPLACE INTO idle_streaks "
                                    + "(owner_uuid, current_streak, last_login_day) VALUES (?, ?, ?)")) {
                        upsert.setString(1, owner.toString());
                        upsert.setInt(2, updated.days());
                        upsert.setString(3, updated.lastDay());
                        upsert.executeUpdate();
                    }
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE wallet_accounts SET coins = coins + ? WHERE uuid = ?")) {
                        update.setLong(1, Math.round(bonus));
                        update.setString(2, owner.toString());
                        if (update.executeUpdate() != 1) {
                            throw new SQLException("Player row is missing");
                        }
                    }
                });
        if (!committed) {
            player.sendMessage(Component.text("[Streak] Reward settlement failed; please relog.",
                    NamedTextColor.RED));
            return;
        }
        streaks.put(owner, updated);
        data.addBalance(bonus);

        // Weekly streak milestone → community feed (via the shared wallet event,
        // so Idle never depends on the Discord plugin directly).
        if (days > 0 && days % 7 == 0) {
            Bukkit.getPluginManager().callEvent(new CommunityNotification(
                    CommunityNotification.Kind.STREAK, owner, player.getName(),
                    "🔥 " + days + "-day streak!",
                    player.getName() + " ล็อกอินต่อเนื่อง " + days + " วันแล้ว!",
                    true, false));
        }

        String currency = plugin.getConfig().getString("currency-name", "Coins");
        player.sendMessage(Component.text()
                .append(Component.text("[Streak] ", NamedTextColor.GOLD))
                .append(Component.text("Day " + days + "! +" + (long) bonus + " " + currency,
                        NamedTextColor.GREEN))
                .build());
    }

}
