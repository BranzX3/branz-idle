package dev.branzx.idle.service.design;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.service.GameDesignService;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.GameStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeasonalChronicleServiceTest {

    @TempDir
    Path temp;

    @Test
    void missedObjectiveProgressesAsCatchUpAndPaysSeasonPointsOnce() throws Exception {
        IdlePlugin plugin = pluginWithCatalog();
        GameStateStore state = new GameStateStore(plugin, mock(Database.class));
        SeasonService seasons = mock(SeasonService.class);
        when(seasons.id()).thenReturn("s1");
        when(seasons.week()).thenReturn(3);
        when(seasons.durationWeeks()).thenReturn(12);
        FeatureControlService controls = mock(FeatureControlService.class);
        UUID owner = UUID.randomUUID();
        when(controls.enabled("seasonal-chronicle", owner)).thenReturn(true);
        ChronicleService chronicle = mock(ChronicleService.class);
        when(chronicle.seasonalPoints(owner)).thenAnswer(ignored ->
                state.getInt(owner, "SEASON", "s1", "chronicle_points", 0));
        SeasonalChronicleService seasonal = new SeasonalChronicleService(plugin, state,
                seasons, controls, chronicle, mock(TelemetryService.class));
        seasonal.loadDefinitions();

        seasonal.advance(owner, "collect", 256);
        SeasonalChronicleService.Objective objective = seasonal.objectives(owner).stream()
                .filter(value -> value.id().equals("discovery_supplies")).findFirst().orElseThrow();
        assertTrue(objective.catchUp());
        assertTrue(objective.completed());

        GameDesignService.Result claimed = seasonal.claim(owner, objective.id());
        assertTrue(claimed.success());
        assertEquals(10, state.getInt(owner, "SEASON", "s1", "chronicle_points", 0));
        assertTrue(!seasonal.claim(owner, objective.id()).success());
    }

    private IdlePlugin pluginWithCatalog() throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/seasonal-objectives.yml")) {
            Files.copy(source, temp.resolve("seasonal-objectives.yml"));
        }
        org.bukkit.configuration.file.YamlConfiguration config =
                new org.bukkit.configuration.file.YamlConfiguration();
        config.set("live-ops.catch-up.weekly-objectives", true);
        IdlePlugin plugin = mock(IdlePlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SeasonalChronicleServiceTest"));
        when(plugin.getResource("seasonal-objectives.yml")).thenAnswer(ignored ->
                getClass().getResourceAsStream("/seasonal-objectives.yml"));
        return plugin;
    }
}
