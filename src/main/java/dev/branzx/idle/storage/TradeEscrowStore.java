package dev.branzx.idle.storage;

import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Durable journal for items removed by direct trades.
 *
 * <p>OPEN rows belong to a live in-memory trade. On startup they are changed
 * to PENDING_DELIVERY for their original owner. Settlement changes all rows
 * to PENDING_DELIVERY for the counterparty in the same transaction that
 * creates the immutable receipt.</p>
 */
public final class TradeEscrowStore {

    interface ItemCodec {
        String encode(ItemStack item);
        ItemStack decode(String encoded);
    }

    public record Entry(String escrowId, String tradeId, UUID owner, UUID recipient,
                        ItemStack item) {
    }

    private final Database database;
    private final ItemCodec codec;

    public TradeEscrowStore(Database database) {
        this(database, new ItemCodec() {
            @Override
            public String encode(ItemStack item) {
                return TradeEscrowStore.encode(item);
            }

            @Override
            public ItemStack decode(String encoded) {
                return TradeEscrowStore.decode(encoded);
            }
        });
    }

    TradeEscrowStore(Database database, ItemCodec codec) {
        this.database = database;
        this.codec = codec;
    }

    public Entry hold(String tradeId, UUID owner, ItemStack item) {
        String escrowId = UUID.randomUUID().toString();
        String encoded = codec.encode(item);
        boolean committed = database.executeTransaction("hold trade escrow " + escrowId, connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idle_trade_escrow "
                            + "(escrow_id, trade_id, owner_uuid, recipient_uuid, item_data, state) "
                            + "VALUES (?, ?, ?, ?, ?, 'OPEN')")) {
                insert.setString(1, escrowId);
                insert.setString(2, tradeId);
                insert.setString(3, owner.toString());
                insert.setString(4, owner.toString());
                insert.setString(5, encoded);
                insert.executeUpdate();
            }
        });
        return committed ? new Entry(escrowId, tradeId, owner, owner, item.clone()) : null;
    }

    public boolean queueReturn(String escrowId) {
        return database.executeTransaction("return trade escrow " + escrowId, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_trade_escrow "
                            + "SET state = 'PENDING_DELIVERY', recipient_uuid = owner_uuid "
                            + "WHERE escrow_id = ? AND state = 'OPEN'")) {
                update.setString(1, escrowId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Escrow row is missing or no longer open: " + escrowId);
                }
            }
        });
    }

    public boolean queueTradeReturn(String tradeId, int expectedItems) {
        return database.executeTransaction("cancel trade escrow " + tradeId, connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_trade_escrow "
                            + "SET state = 'PENDING_DELIVERY', recipient_uuid = owner_uuid "
                            + "WHERE trade_id = ? AND state = 'OPEN'")) {
                update.setString(1, tradeId);
                int changed = update.executeUpdate();
                if (changed != expectedItems) {
                    throw new SQLException("Expected " + expectedItems
                            + " open escrow rows but changed " + changed);
                }
            }
        });
    }

    public boolean settle(String tradeId, UUID playerA, UUID playerB,
                          String offerA, String offerB, int expectedItems) {
        return database.executeTransaction("settle trade " + tradeId, connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idle_trade_receipts "
                            + "(trade_id, player_a, player_b, offer_a, offer_b) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, tradeId);
                insert.setString(2, playerA.toString());
                insert.setString(3, playerB.toString());
                insert.setString(4, offerA);
                insert.setString(5, offerB);
                insert.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_trade_escrow SET state = 'PENDING_DELIVERY', "
                            + "recipient_uuid = CASE WHEN owner_uuid = ? THEN ? ELSE ? END "
                            + "WHERE trade_id = ? AND state = 'OPEN' "
                            + "AND owner_uuid IN (?, ?)")) {
                update.setString(1, playerA.toString());
                update.setString(2, playerB.toString());
                update.setString(3, playerA.toString());
                update.setString(4, tradeId);
                update.setString(5, playerA.toString());
                update.setString(6, playerB.toString());
                int changed = update.executeUpdate();
                if (changed != expectedItems) {
                    throw new SQLException("Expected " + expectedItems
                            + " open escrow rows but changed " + changed);
                }
            }
        });
    }

    /**
     * Converts items from trades lost with the previous process into refunds.
     * Safe to call repeatedly.
     */
    public int recoverInterruptedTrades() {
        final int[] recovered = {0};
        boolean committed = database.executeTransaction("recover interrupted trade escrow", connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_trade_escrow "
                            + "SET state = 'PENDING_DELIVERY', recipient_uuid = owner_uuid "
                            + "WHERE state = 'OPEN'")) {
                recovered[0] = update.executeUpdate();
            }
        });
        return committed ? recovered[0] : -1;
    }

    public List<Entry> pending(UUID recipient) {
        List<Entry> entries = new ArrayList<>();
        try (var connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT escrow_id, trade_id, owner_uuid, recipient_uuid, item_data "
                             + "FROM idle_trade_escrow "
                             + "WHERE recipient_uuid = ? AND state = 'PENDING_DELIVERY' "
                             + "ORDER BY created_at, escrow_id")) {
            select.setString(1, recipient.toString());
            try (var result = select.executeQuery()) {
                while (result.next()) {
                    entries.add(new Entry(
                            result.getString("escrow_id"),
                            result.getString("trade_id"),
                            UUID.fromString(result.getString("owner_uuid")),
                            UUID.fromString(result.getString("recipient_uuid")),
                            codec.decode(result.getString("item_data"))));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not load pending trade escrow", e);
        }
        return entries;
    }

    public boolean acknowledge(List<Entry> entries) {
        if (entries.isEmpty()) return true;
        return database.executeTransaction("acknowledge trade escrow delivery", connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM idle_trade_escrow "
                            + "WHERE escrow_id = ? AND state = 'PENDING_DELIVERY'")) {
                for (Entry entry : entries) {
                    delete.setString(1, entry.escrowId());
                    delete.addBatch();
                }
                int[] changed = delete.executeBatch();
                for (int count : changed) {
                    if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                        throw new SQLException("A pending escrow row was missing during delivery ack");
                    }
                }
            }
        });
    }

    public static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack decode(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }
}
