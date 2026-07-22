package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Single source of truth for "does this plugin operate in this world?".
 *
 * <p>Claiming was the only thing that ever consulted the world list, which let
 * commands, menus, NPCs and payouts leak into worlds the server owner never
 * opted in to. Everything that touches the world or pays a player now goes
 * through here instead.
 *
 * <p>The canonical key is the root-level {@code worlds} list. {@code
 * claims.worlds} is still honoured so existing configs keep working. An empty
 * list means "any Overworld", matching the historical default.
 */
public final class WorldGate {

    private final IdlePlugin plugin;

    public WorldGate(IdlePlugin plugin) {
        this.plugin = plugin;
    }

    private List<String> allowed() {
        List<String> roots = plugin.getConfig().getStringList("worlds");
        return roots.isEmpty() ? plugin.getConfig().getStringList("claims.worlds") : roots;
    }

    public boolean isEnabled(World world) {
        if (world == null) {
            return false;
        }
        List<String> allowed = allowed();
        if (allowed.isEmpty()) {
            return world.getEnvironment() == World.Environment.NORMAL;
        }
        return allowed.contains(world.getName());
    }

    /**
     * World-name overload for records that store a name rather than a handle.
     * An unloaded world cannot be resolved, so it is treated as disabled —
     * nothing can legitimately act on it anyway.
     */
    public boolean isEnabled(String worldName) {
        if (worldName == null) {
            return false;
        }
        List<String> allowed = allowed();
        if (!allowed.isEmpty()) {
            return allowed.contains(worldName);
        }
        return isEnabled(plugin.getServer().getWorld(worldName));
    }

    /**
     * Gate a player action, telling them why it was refused. Returns true when
     * the action may proceed.
     */
    public boolean check(Player player) {
        if (isEnabled(player.getWorld())) {
            return true;
        }
        player.sendMessage(Component.text(
                "Idle is not active in this world.", NamedTextColor.RED));
        return false;
    }
}
