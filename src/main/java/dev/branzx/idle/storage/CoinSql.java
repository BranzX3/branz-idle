package dev.branzx.idle.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Relative, floor-guarded Coin writes against the shared {@code wallet_accounts}
 * table, on a caller-supplied connection so they can be part of the gameplay
 * transaction they pay for.
 *
 * <p><b>Never write an absolute Coin balance.</b> Coins live in a table shared
 * with other backends and the Discord storefront, so a grant can land at any
 * moment. Writing a whole balance computed from this server's cached snapshot
 * would silently erase it. Every mutation here is a delta, and a debit carries
 * its own floor in the WHERE clause so it can never overdraw however stale this
 * server's view was.
 */
public final class CoinSql {

    private CoinSql() {
    }

    /** Subtracts {@code amount} Coins; throws if that would overdraw. */
    public static void debit(Connection connection, UUID owner, long amount) throws SQLException {
        if (amount <= 0) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_accounts SET coins = coins - ? WHERE uuid = ? AND coins >= ?")) {
            update.setLong(1, amount);
            update.setString(2, owner.toString());
            update.setLong(3, amount);
            if (update.executeUpdate() != 1) {
                throw new SQLException("Insufficient Coins or missing wallet row for " + owner);
            }
        }
    }

    /** Adds {@code amount} Coins; throws if the account row is missing. */
    public static void credit(Connection connection, UUID owner, long amount) throws SQLException {
        if (amount <= 0) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE wallet_accounts SET coins = coins + ? WHERE uuid = ?")) {
            update.setLong(1, amount);
            update.setString(2, owner.toString());
            if (update.executeUpdate() != 1) {
                throw new SQLException("Wallet row is missing for " + owner);
            }
        }
    }
}
