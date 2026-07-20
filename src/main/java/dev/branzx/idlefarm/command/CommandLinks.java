package dev.branzx.idlefarm.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Shared chat actions for commands shown to players.
 *
 * <p>Complete commands run immediately. Commands that still need arguments
 * are placed into the chat input so the player can finish them safely.</p>
 */
public final class CommandLinks {

    private CommandLinks() {
    }

    public static Component run(String label, String command) {
        return action(label, command, true);
    }

    public static Component suggest(String label, String command) {
        return action(label, command, false);
    }

    private static Component action(String label, String command, boolean complete) {
        String normalized = command.startsWith("/") ? command : "/" + command;
        String hover = complete ? "Click to run " + normalized : "Click to fill " + normalized;
        return Component.text(label, NamedTextColor.YELLOW)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(complete
                        ? ClickEvent.runCommand(normalized)
                        : ClickEvent.suggestCommand(normalized))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }
}
