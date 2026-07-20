package dev.branzx.idlefarm.gui;

import dev.branzx.idlefarm.IdleFarmPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reusable "type a value in chat" flow for GUIs. A menu closes, asks the
 * player to type a number (or 'cancel'), and the next chat line is captured
 * (never broadcast) and handed back on the main thread. Used anywhere an
 * exact value beats click-nudging.
 */
public final class ChatPrompt implements Listener {

    private record Pending(Consumer<String> onInput, Runnable onCancel) {
    }

    private final IdleFarmPlugin plugin;
    private final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();

    public ChatPrompt(IdleFarmPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Prompts the player to type something. {@code onInput} receives the raw
     * line on the main thread; {@code onCancel} runs if they type "cancel".
     */
    public void request(Player player, String message, Consumer<String> onInput, Runnable onCancel) {
        player.closeInventory();
        waiting.put(player.getUniqueId(), new Pending(onInput, onCancel));
        player.sendMessage(Component.text("» " + message, NamedTextColor.YELLOW));
        player.sendMessage(Component.text("  Type a value in chat, or 'cancel'.", NamedTextColor.GRAY));
    }

    /** Convenience: prompt for a non-negative number. */
    public void requestNumber(Player player, String message, Consumer<Double> onNumber, Runnable onCancel) {
        request(player, message, raw -> {
            try {
                onNumber.accept(Math.max(0, Double.parseDouble(raw.trim())));
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Not a number: " + raw, NamedTextColor.RED));
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        }, onCancel);
    }

    /** Prompt for a signed number, useful for audited admin adjustments. */
    public void requestSignedNumber(Player player, String message,
                                    Consumer<Double> onNumber, Runnable onCancel) {
        request(player, message, raw -> {
            try {
                onNumber.accept(Double.parseDouble(raw.trim()));
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Not a number: " + raw, NamedTextColor.RED));
                if (onCancel != null) {
                    onCancel.run();
                }
            }
        }, onCancel);
    }

    public boolean isWaiting(UUID uuid) {
        return waiting.containsKey(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Pending pending = waiting.remove(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true); // don't broadcast the value
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        // Hop back to the main thread for all Bukkit/GUI work.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (message.equalsIgnoreCase("cancel")) {
                if (pending.onCancel() != null) {
                    pending.onCancel().run();
                }
            } else {
                pending.onInput().accept(message);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
