package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.PlayerDataStore;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Credits are bought with real money, so a grant made outside this server —
 * a Discord top-up bot, an admin on another backend — must survive whatever
 * this server does next. The old cache wrote whole wallet rows back from a
 * boot-time snapshot, which erased exactly those grants.
 */
class CreditWalletIntegrationTest {

    @TempDir
    Path temp;

    private Database database;
    private CreditService credits;
    private UUID owner;

    @BeforeEach
    void setUp() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("CreditWalletIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        database = new Database(plugin);
        database.init();

        GameDesignService gameDesign = mock(GameDesignService.class);
        when(gameDesign.seasonId()).thenReturn("s1");
        AuditService audit = mock(AuditService.class);
        PlayerDataStore players = new PlayerDataStore(plugin, database);

        credits = new CreditService(plugin, database, players, audit, gameDesign);
        owner = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    /**
     * Blocks until every queued write has run. The write queue is a single
     * thread, so a no-op transaction behind them is a barrier.
     */
    private void drainWrites() {
        assertTrue(database.executeTransaction("drain", connection -> { }));
    }

    /** Stands in for a Discord bot writing straight to the shared database. */
    private void externalGrant(long amount) throws Exception {
        try (var connection = database.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE idle_credit_wallet SET credits = credits + ? WHERE owner_uuid = ?")) {
            update.setLong(1, amount);
            update.setString(2, owner.toString());
            assertEquals(1, update.executeUpdate(), "wallet row should already exist");
        }
    }

    @Test
    void topUpIsIdempotentPerTransactionId() {
        assertTrue(credits.adjust(owner, 500, "PURCHASE", "omise_ch_123", "{}"));
        assertEquals(500, credits.balance(owner));

        assertFalse(credits.adjust(owner, 500, "PURCHASE", "omise_ch_123", "{}"),
                "a replayed webhook must not mint Credits twice");
        assertEquals(500, credits.balance(owner));

        assertTrue(credits.adjust(owner, 500, "PURCHASE", "omise_ch_124", "{}"));
        assertEquals(1_000, credits.balance(owner));
    }

    @Test
    void anExternalGrantSurvivesThisServersNextWrite() throws Exception {
        assertTrue(credits.adjust(owner, 100, "PURCHASE", "tx_seed", "{}"));
        assertEquals(100, credits.balance(owner)); // now cached

        externalGrant(900);

        // The payout tick writes season earnings for every online player. It
        // must not carry the stale 100 back over the top of the grant.
        credits.recordCoinsEarned(owner, 250);
        drainWrites();

        assertEquals(1_000, credits.balance(owner),
                "the externally granted Credits must still be there");
    }

    @Test
    void deductionCannotOverdrawEvenFromAStaleView() throws Exception {
        assertTrue(credits.adjust(owner, 100, "PURCHASE", "tx_seed", "{}"));
        assertEquals(100, credits.balance(owner));

        // Spent somewhere else after this server cached the balance.
        externalGrant(-90);

        assertFalse(credits.adjust(owner, -50, "SPEND", "tx_spend", "{}"),
                "the floor is in the WHERE clause, so a stale view cannot overdraw");
        assertEquals(10, credits.balance(owner));
    }

    @Test
    void balanceIsReadThroughForAPlayerThisServerHasNeverSeen() throws Exception {
        // No adjust() first: the wallet row does not exist yet.
        assertEquals(0, credits.balance(owner));

        externalGrant(750);
        credits.invalidate(owner);

        assertEquals(750, credits.balance(owner));
    }
}
