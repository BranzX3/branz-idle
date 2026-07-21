package dev.branzx.idle.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCatalogTest {

    @Test
    void canonicalNamesAreUniqueWithinEachAudience() {
        var playerNames = new HashSet<String>();
        CommandCatalog.playerEntries().forEach(entry ->
                assertTrue(playerNames.add(entry.name()), "duplicate player command " + entry.name()));

        var adminNames = new HashSet<String>();
        CommandCatalog.adminEntries().forEach(entry ->
                assertTrue(adminNames.add(entry.name()), "duplicate admin command " + entry.name()));
    }

    @Test
    void aliasesResolveToTheirCanonicalCommand() {
        assertEquals("progress", CommandCatalog.findPlayer("chronicle").name());
        assertEquals("progress", CommandCatalog.findPlayer("journal").name());
        assertNotNull(CommandCatalog.findAdmin("dashboard"));
    }

    @Test
    void suggestionsFilterByPrefix() {
        var suggestions = CommandCatalog.suggestions(CommandCatalog.playerEntries(), "co");
        assertTrue(suggestions.contains("collect"));
        assertTrue(suggestions.contains("commission"));
        assertFalse(suggestions.contains("warehouse"));
    }

    @Test
    void everyAdminCommandHasScopedPermission() {
        CommandCatalog.adminEntries().forEach(entry ->
                assertTrue(entry.permission().startsWith("idle.admin")));
    }
}
