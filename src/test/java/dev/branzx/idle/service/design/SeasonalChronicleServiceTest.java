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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void aFinishedSeasonIsArchivedOnceAndSurvivesTheNextSeasonsWallet() throws Exception {
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
        List<SeasonalChronicleService.Participation> announced = new ArrayList<>();
        seasonal.setArchiveNotifier((ignored, participation) -> announced.add(participation));

        // Play season 1: complete and claim one objective, then log in again.
        seasonal.archiveFinishedSeason(owner);
        seasonal.advance(owner, "collect", 256);
        String objectiveId = seasonal.objectives(owner).stream()
                .filter(SeasonalChronicleService.Objective::completed).findFirst().orElseThrow().id();
        assertTrue(seasonal.claim(owner, objectiveId).success());
        seasonal.archiveFinishedSeason(owner);
        assertTrue(seasonal.participation(owner).isEmpty(), "the current season is not history");

        when(seasons.id()).thenReturn("s2");
        seasonal.archiveFinishedSeason(owner);
        seasonal.archiveFinishedSeason(owner);

        List<SeasonalChronicleService.Participation> history = seasonal.participation(owner);
        assertEquals(1, history.size(), "logging in twice must not archive twice");
        assertEquals("s1", history.get(0).seasonId());
        assertEquals(10, history.get(0).points());
        assertEquals(1, history.get(0).objectives());
        assertEquals(1, announced.size());
        // The new season starts empty without erasing what season 1 recorded.
        assertEquals(0, state.getInt(owner, "SEASON", "s2", "chronicle_points", 0));
        verify(chronicle).count(owner, "seasons_participated", 1);
    }

    @Test
    void aSeasonThePlayerNeverTouchedLeavesNoBlankPage() throws Exception {
        IdlePlugin plugin = pluginWithCatalog();
        GameStateStore state = new GameStateStore(plugin, mock(Database.class));
        SeasonService seasons = mock(SeasonService.class);
        when(seasons.id()).thenReturn("s1");
        when(seasons.week()).thenReturn(1);
        when(seasons.durationWeeks()).thenReturn(12);
        ChronicleService chronicle = mock(ChronicleService.class);
        SeasonalChronicleService seasonal = new SeasonalChronicleService(plugin, state, seasons,
                mock(FeatureControlService.class), chronicle, mock(TelemetryService.class));
        seasonal.loadDefinitions();
        UUID owner = UUID.randomUUID();

        seasonal.archiveFinishedSeason(owner);
        when(seasons.id()).thenReturn("s2");
        seasonal.archiveFinishedSeason(owner);

        assertTrue(seasonal.participation(owner).isEmpty());
        verify(chronicle, never()).count(owner, "seasons_participated", 1);
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
