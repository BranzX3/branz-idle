package dev.branzx.idle.storage;

import dev.branzx.idle.IdlePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeEscrowStoreIntegrationTest {

    @TempDir
    Path temp;

    @Test
    void interruptedOpenEscrowIsRefundedAfterRestart() {
        IdlePlugin plugin = plugin();
        UUID owner = UUID.randomUUID();
        String tradeId = UUID.randomUUID().toString();

        Database first = new Database(plugin);
        first.init();
        TestItemCodec codec = new TestItemCodec();
        ItemStack diamonds = item(codec, "diamonds");
        TradeEscrowStore.Entry held;
        try {
            held = new TradeEscrowStore(first, codec).hold(tradeId, owner, diamonds);
            assertNotNull(held);
        } finally {
            first.shutdown();
        }

        Database restarted = new Database(plugin);
        restarted.init();
        try {
            TradeEscrowStore store = new TradeEscrowStore(restarted, codec);
            assertEquals(1, store.recoverInterruptedTrades());
            assertEquals(0, store.recoverInterruptedTrades(), "recovery must be idempotent");

            var pending = store.pending(owner);
            assertEquals(1, pending.size());
            assertEquals(held.escrowId(), pending.getFirst().escrowId());
            assertEquals(diamonds, pending.getFirst().item());

            assertTrue(store.acknowledge(pending));
            assertTrue(store.pending(owner).isEmpty());
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void settlementCommitsReceiptAndCounterpartyDeliveryTogether() throws Exception {
        IdlePlugin plugin = plugin();
        Database database = new Database(plugin);
        database.init();
        try {
            TestItemCodec codec = new TestItemCodec();
            TradeEscrowStore store = new TradeEscrowStore(database, codec);
            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            String tradeId = UUID.randomUUID().toString();
            ItemStack iron = item(codec, "iron");
            ItemStack emerald = item(codec, "emerald");
            assertNotNull(store.hold(tradeId, playerA, iron));
            assertNotNull(store.hold(tradeId, playerB, emerald));

            assertTrue(store.settle(tradeId, playerA, playerB, "offer-a", "offer-b", 2));
            assertEquals(emerald, store.pending(playerA).getFirst().item());
            assertEquals(iron, store.pending(playerB).getFirst().item());

            try (var connection = database.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT offer_a, offer_b FROM idle_trade_receipts WHERE trade_id = ?")) {
                select.setString(1, tradeId);
                try (var result = select.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("offer-a", result.getString(1));
                    assertEquals("offer-b", result.getString(2));
                }
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void receiptConflictRollsBackEscrowOwnershipTransition() throws Exception {
        IdlePlugin plugin = plugin();
        Database database = new Database(plugin);
        database.init();
        try {
            TestItemCodec codec = new TestItemCodec();
            TradeEscrowStore store = new TradeEscrowStore(database, codec);
            UUID playerA = UUID.randomUUID();
            UUID playerB = UUID.randomUUID();
            String tradeId = UUID.randomUUID().toString();
            TradeEscrowStore.Entry held = store.hold(tradeId, playerA, item(codec, "gold"));
            assertNotNull(held);

            try (var connection = database.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO idle_trade_receipts "
                                 + "(trade_id, player_a, player_b, offer_a, offer_b) "
                                 + "VALUES (?, ?, ?, '', '')")) {
                insert.setString(1, tradeId);
                insert.setString(2, playerA.toString());
                insert.setString(3, playerB.toString());
                insert.executeUpdate();
            }

            assertFalse(store.settle(tradeId, playerA, playerB, "offer-a", "", 1));
            assertTrue(store.pending(playerB).isEmpty());
            assertEquals(1, store.recoverInterruptedTrades(),
                    "failed settlement must leave the item OPEN for owner recovery");
            assertEquals(held.escrowId(), store.pending(playerA).getFirst().escrowId());
        } finally {
            database.shutdown();
        }
    }

    private IdlePlugin plugin() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(temp.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("TradeEscrowStoreIntegrationTest"));
        when(config.getString("storage.type", "sqlite")).thenReturn("sqlite");
        return plugin;
    }

    private static ItemStack item(TestItemCodec codec, String encoded) {
        ItemStack item = mock(ItemStack.class);
        when(item.clone()).thenReturn(item);
        codec.add(item, encoded);
        return item;
    }

    private static final class TestItemCodec implements TradeEscrowStore.ItemCodec {
        private final Map<ItemStack, String> encoded = new IdentityHashMap<>();
        private final Map<String, ItemStack> decoded = new HashMap<>();

        void add(ItemStack item, String value) {
            encoded.put(item, value);
            decoded.put(value, item);
        }

        @Override
        public String encode(ItemStack item) {
            return encoded.get(item);
        }

        @Override
        public ItemStack decode(String value) {
            return decoded.get(value);
        }
    }
}
