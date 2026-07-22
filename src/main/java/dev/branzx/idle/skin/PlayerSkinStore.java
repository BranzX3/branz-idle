package dev.branzx.idle.skin;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which skins each player has unlocked.
 *
 * <p>Kept separate from {@code idle_nodes.skin_id} because unlocking and
 * wearing are separate events: a player unlocks a skin once and may then
 * apply it to any number of nodes.</p>
 *
 * <p>Fully cached: unlock checks run on every skin menu draw, and the set per
 * player is tiny.</p>
 */
public final class PlayerSkinStore {

    private final IdlePlugin plugin;
    private final Database database;
    private final Map<UUID, Set<String>> unlocked = new ConcurrentHashMap<>();

    public PlayerSkinStore(IdlePlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT uuid, skin_id FROM idle_player_skins");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                unlocked.computeIfAbsent(UUID.fromString(rs.getString("uuid")),
                                k -> ConcurrentHashMap.newKeySet())
                        .add(rs.getString("skin_id").toLowerCase(Locale.ROOT));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load player skins: " + e.getMessage());
        }
    }

    /**
     * True when the player may wear this skin. Default-unlock skins are
     * available to everyone without a grant row, which is what makes a
     * contest winner's skin server-wide by simply shipping it as default.
     */
    public boolean canUse(UUID player, SkinDefinition skin) {
        if (skin == null) {
            return false;
        }
        if (skin.isDefaultUnlock()) {
            return true;
        }
        Set<String> owned = unlocked.get(player);
        return owned != null && owned.contains(skin.getId().toLowerCase(Locale.ROOT));
    }

    public Set<String> unlockedIds(UUID player) {
        return Set.copyOf(unlocked.getOrDefault(player, Set.of()));
    }

    public void grant(UUID player, String skinId) {
        String normalized = skinId.toLowerCase(Locale.ROOT);
        if (!unlocked.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(normalized)) {
            return; // already unlocked; do not spend a write
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "REPLACE INTO idle_player_skins (uuid, skin_id, granted_at) VALUES (?, ?, ?)")) {
                insert.setString(1, player.toString());
                insert.setString(2, normalized);
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to grant skin " + normalized + " to "
                        + player + ": " + e.getMessage());
            }
        });
    }

    public void revoke(UUID player, String skinId) {
        String normalized = skinId.toLowerCase(Locale.ROOT);
        Set<String> owned = unlocked.get(player);
        if (owned != null) {
            owned.remove(normalized);
        }
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement delete = connection.prepareStatement(
                         "DELETE FROM idle_player_skins WHERE uuid = ? AND skin_id = ?")) {
                delete.setString(1, player.toString());
                delete.setString(2, normalized);
                delete.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to revoke skin " + normalized + " from "
                        + player + ": " + e.getMessage());
            }
        });
    }
}
