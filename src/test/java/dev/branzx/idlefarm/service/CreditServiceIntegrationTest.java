package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditServiceIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void zeroCreditCheckoutIsStillIdempotent() {
        IdleFarmPlugin plugin = mock(IdleFarmPlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CreditServiceIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");
        when(config.getLong("credits.coin-offset-per-credit", 20)).thenReturn(20L);
        when(config.getLong("credits.max-offset-percent", 15)).thenReturn(15L);
        when(config.getLong("credits.season-offset-cap", 30_000)).thenReturn(30_000L);

        Database database = new Database(plugin);
        database.init();
        try {
            UUID owner = UUID.randomUUID();
            PlayerDataStore players = new PlayerDataStore(plugin, database);
            PlayerData player = players.loadOrCreateSync(owner, "Tester");
            player.addBalance(1_000);
            players.saveSync(player);

            GameDesignService design = mock(GameDesignService.class);
            when(design.seasonId()).thenReturn("preseason");
            CreditService credits = new CreditService(plugin, database, players,
                    mock(AuditService.class), design);

            CreditService.Checkout first =
                    credits.hybridPay(owner, 100, 0, "warehouse", "checkout-1");
            CreditService.Checkout replay =
                    credits.hybridPay(owner, 100, 0, "warehouse", "checkout-1");

            assertTrue(first.success());
            assertFalse(replay.success());
            assertEquals(900.0, player.getBalance());
            assertEquals(1, credits.historySync(owner, 10).size());
        } finally {
            database.shutdown();
        }
    }
}
