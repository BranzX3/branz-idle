package dev.branzx.idlefarm.command;

import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CommandLinksTest {

    @Test
    void completeCommandRunsImmediately() {
        var component = CommandLinks.run("[Open]", "idle");

        assertNotNull(component.clickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, component.clickEvent().action());
        assertEquals("/idle",
                ((ClickEvent.Payload.Text) component.clickEvent().payload()).value());
    }

    @Test
    void incompleteCommandIsSuggestedForSafeCompletion() {
        var component = CommandLinks.suggest("/idle trust <player>", "/idle trust ");

        assertNotNull(component.clickEvent());
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, component.clickEvent().action());
        assertEquals("/idle trust ",
                ((ClickEvent.Payload.Text) component.clickEvent().payload()).value());
    }
}
