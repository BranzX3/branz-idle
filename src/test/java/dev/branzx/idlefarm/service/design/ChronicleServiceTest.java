package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.service.AuditService;
import dev.branzx.idlefarm.service.GameDesignService;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.GameStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChronicleServiceTest {

    @TempDir
    Path temp;

    @Test
    void multiCounterAchievementCompletesOnlyAfterEveryRequirement() throws Exception {
        IdleFarmPlugin plugin = pluginWithCatalog("achievements.yml");
        GameStateStore state = new GameStateStore(plugin, mock(Database.class));
        ChronicleService chronicle = new ChronicleService(plugin, state,
                mock(AuditService.class), mock(TelemetryService.class),
                mock(SeasonService.class), (owner, amount) -> { });
        chronicle.loadDefinitions();
        UUID owner = UUID.randomUUID();

        chronicle.countMax(owner, "node_level_MINING", 10);
        chronicle.count(owner, "produced_MINING", 500);
        assertFalse(find(chronicle, owner, "mining_bronze").completed());

        chronicle.count(owner, "events_MINING", 1);
        assertTrue(find(chronicle, owner, "mining_bronze").completed());

        GameDesignService.Result result = chronicle.claim(owner, "mining_bronze");
        assertTrue(result.success());
        assertEquals(4, chronicle.points(owner));
        assertFalse(chronicle.claim(owner, "mining_bronze").success());
    }

    private GameDesignService.Achievement find(ChronicleService chronicle,
                                                UUID owner, String id) {
        return chronicle.achievements(owner).stream()
                .filter(achievement -> achievement.id().equals(id))
                .findFirst().orElseThrow();
    }

    private IdleFarmPlugin pluginWithCatalog(String name) throws Exception {
        try (InputStream source = getClass().getResourceAsStream("/" + name)) {
            Files.copy(source, temp.resolve(name));
        }
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ChronicleServiceTest"));
        when(plugin.getResource(name)).thenAnswer(ignored ->
                getClass().getResourceAsStream("/" + name));
        return plugin;
    }
}
