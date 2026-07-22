package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gate decides where the whole plugin exists. Getting it wrong either
 * leaks gameplay into worlds the owner never opted in to, or — worse on an
 * upgrade — silently switches an existing restriction off.
 */
class WorldGateTest {

    private FileConfiguration config;
    private WorldGate gate;

    private World world(String name, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
    }

    @BeforeEach
    void setUp() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getStringList("worlds")).thenReturn(List.of());
        when(config.getStringList("claims.worlds")).thenReturn(List.of());
        gate = new WorldGate(plugin);
    }

    @Test
    void emptyListAllowsOverworldsOnly() {
        assertTrue(gate.isEnabled(world("world", World.Environment.NORMAL)));
        assertFalse(gate.isEnabled(world("world_nether", World.Environment.NETHER)));
        assertFalse(gate.isEnabled(world("world_the_end", World.Environment.THE_END)));
    }

    @Test
    void listedWorldsWinOverEnvironment() {
        when(config.getStringList("worlds")).thenReturn(List.of("idle_world", "hell"));

        assertTrue(gate.isEnabled(world("idle_world", World.Environment.NORMAL)));
        // An explicitly listed Nether is in play; an unlisted Overworld is not.
        assertTrue(gate.isEnabled(world("hell", World.Environment.NETHER)));
        assertFalse(gate.isEnabled(world("world", World.Environment.NORMAL)));
    }

    @Test
    void fallsBackToLegacyClaimsWorldsWhenRootIsEmpty() {
        when(config.getStringList("claims.worlds")).thenReturn(List.of("legacy"));

        assertTrue(gate.isEnabled(world("legacy", World.Environment.NORMAL)));
        assertFalse(gate.isEnabled(world("world", World.Environment.NORMAL)));
    }

    @Test
    void rootListTakesPrecedenceOverLegacyKey() {
        when(config.getStringList("worlds")).thenReturn(List.of("current"));
        when(config.getStringList("claims.worlds")).thenReturn(List.of("legacy"));

        assertTrue(gate.isEnabled(world("current", World.Environment.NORMAL)));
        assertFalse(gate.isEnabled(world("legacy", World.Environment.NORMAL)));
    }

    @Test
    void nullWorldIsNeverEnabled() {
        assertFalse(gate.isEnabled((World) null));
        assertFalse(gate.isEnabled((String) null));
    }

    @Test
    void nameOverloadMatchesTheConfiguredList() {
        when(config.getStringList("worlds")).thenReturn(List.of("idle_world"));

        assertTrue(gate.isEnabled("idle_world"));
        assertFalse(gate.isEnabled("somewhere_else"));
    }
}
