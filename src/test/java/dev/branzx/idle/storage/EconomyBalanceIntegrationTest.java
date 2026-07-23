package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
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
 * The Vault provider hands other plugins — and, on a Velocity network, other
 * backends — a way to move Coins for players this server is not holding in
 * memory. Those writes must land in the database and must never overdraw.
 */
class EconomyBalanceIntegrationTest {

    @TempDir
    Path temp;

    private Database database;
    private PlayerDataStore players;
    private UUID owner;

    @BeforeEach
    void setUp() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EconomyBalanceIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");

        database = new Database(plugin);
        database.init();
        players = new PlayerDataStore(plugin, database);
        owner = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private double storedBalance(UUID uuid) throws Exception {
        try (var connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT coins FROM wallet_accounts WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (var rs = select.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : -1;
            }
        }
    }

    /** Logs the player in, gives them Coins, then logs them out again. */
    private void seedOfflinePlayerWith(double balance) {
        PlayerData data = players.loadOrCreateSync(owner, "Tester");
        data.addBalance(balance);
        players.unload(owner);
    }

    @Test
    void offlineDepositAndWithdrawReachTheDatabase() throws Exception {
        seedOfflinePlayerWith(1_000);

        assertTrue(players.deposit(owner, 250));
        assertEquals(1_250.0, storedBalance(owner));

        assertTrue(players.withdraw(owner, 400));
        assertEquals(850.0, storedBalance(owner));
        assertEquals(850.0, players.balanceOf(owner));
    }

    @Test
    void offlineWithdrawWillNotOverdraw() throws Exception {
        seedOfflinePlayerWith(100);

        // Coins are whole numbers now, so an overdraw is one Coin past the
        // balance; the refusal is part of the UPDATE, so nothing is taken.
        assertFalse(players.withdraw(owner, 101));
        assertEquals(100.0, storedBalance(owner));
    }

    @Test
    void onlinePlayerIsServedFromTheCacheAndStillPersists() throws Exception {
        PlayerData data = players.loadOrCreateSync(owner, "Tester");
        data.addBalance(500);

        assertTrue(players.withdraw(owner, 200));
        assertEquals(300.0, data.getBalance());
        assertEquals(300.0, players.balanceOf(owner));

        assertFalse(players.withdraw(owner, 301), "cache path must refuse an overdraw too");
        assertEquals(300.0, data.getBalance());

        players.unload(owner);
        assertEquals(300.0, storedBalance(owner));
    }

    @Test
    void unknownPlayerHasNoAccountAndNoBalance() {
        UUID stranger = UUID.randomUUID();

        assertFalse(players.accountExists(stranger));
        assertEquals(0.0, players.balanceOf(stranger));
        // Vault callers expect a deposit to a missing account to fail rather
        // than silently create one.
        assertFalse(players.deposit(stranger, 10));

        assertTrue(players.createAccount(stranger, "Stranger"));
        assertTrue(players.accountExists(stranger));
        assertFalse(players.createAccount(stranger, "Stranger"), "must not create twice");
        assertTrue(players.deposit(stranger, 10));
        assertEquals(10.0, players.balanceOf(stranger));
    }
}
