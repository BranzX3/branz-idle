package dev.branzx.idlefarm.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Sends one alert line to every online admin holding the given scope
 * permission (the compatibility parent {@code idlefarm.admin} also passes).
 * Alerts are read-only pointers: the click action is always an inspection
 * command, never a mutation.
 */
public final class AdminAlerts {

    private AdminAlerts() {
    }

    public static void broadcast(String scopePermission, Component message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(scopePermission)
                    || online.hasPermission("idlefarm.admin")) {
                online.sendMessage(message);
            }
        }
    }
}
