package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeasonServiceTest {

    @Test
    void configuredWeekOverridesModifierRotation() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("season.id", "test-season");
        config.set("season.start-date", LocalDate.now(GameClock.GAME_ZONE).minusDays(21).toString());
        config.set("season.duration-weeks", 12);
        config.set("season.weeks.4.phase", "Development");
        config.set("season.weeks.4.modifier", "mixed-crews");
        config.set("season.weeks.4.event-family", "Crews");
        IdlePlugin plugin = mock(IdlePlugin.class);
        when(plugin.getConfig()).thenReturn(config);

        SeasonService seasons = new SeasonService(plugin);
        assertEquals(4, seasons.week());
        assertEquals("MIXED_CREWS", seasons.modifier());
        assertEquals("CREWS", seasons.schedule().eventFamily());
        assertTrue(seasons.validationErrors().isEmpty());
    }
}
