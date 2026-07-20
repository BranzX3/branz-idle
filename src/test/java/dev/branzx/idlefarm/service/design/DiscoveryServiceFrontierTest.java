package dev.branzx.idlefarm.service.design;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscoveryServiceFrontierTest {

    @Test
    void frontierMaterialsUseAccountMonthlyCapAndEmitAuditTelemetry() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("frontier.material-monthly-cap", 2);
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DiscoveryServiceFrontierTest"));
        TelemetryService telemetry = mock(TelemetryService.class);
        DiscoveryService discovery = new DiscoveryService(plugin, mock(Database.class), telemetry);
        UUID owner = UUID.randomUUID();

        assertTrue(discovery.allowResource(owner, "AETHER_ORE", 101));
        assertTrue(discovery.allowResource(owner, "AETHER_ORE", 101));
        assertFalse(discovery.allowResource(owner, "AETHER_ORE", 101));
        verify(telemetry).record(eq(owner), eq("FRONTIER_CAP_HIT"),
                startsWith("{\"material\":\"AETHER_ORE\""));
    }
}
