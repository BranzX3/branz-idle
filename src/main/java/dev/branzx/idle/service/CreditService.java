package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.Database;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integer, non-transferable Credit wallet with immutable ledger and bounded
 * Hybrid Pay. This class intentionally has no Credit-to-Coin conversion API.
 *
 * <p><b>The database row is authoritative, never this cache.</b> Credits are
 * bought with real money and may be granted from outside this server — a
 * payment webhook, an admin on a sibling backend — at any moment, including
 * while the owner is online here. So every mutation is a relative UPDATE
 * ({@code credits = credits + ?}) guarded by its own precondition, and the
 * cached copy is dropped immediately afterwards to be re-read on demand.
 *
 * <p>Writing a whole row back from a cached snapshot would silently erase any
 * grant this server had not seen. That is paid currency disappearing, so it is
 * worth the extra query to never do it.
 */
public final class CreditService {

    public record Checkout(boolean success, String message, long coinsCharged, long creditsCharged) {
        static Checkout fail(String message) { return new Checkout(false, message, 0, 0); }
    }

    public record LedgerEntry(String transactionId, String type, long amount,
                              String detail, String createdAt) {
    }

    private record Wallet(long credits, String season, long seasonOffset, long seasonCoinsEarned) {
    }

    private static final String SELECT_WALLET =
            "SELECT credits, season_id, season_coin_offset, season_coins_earned "
                    + "FROM idle_credit_wallet WHERE owner_uuid = ?";

    private final IdlePlugin plugin;
    private final Database database;
    private final PlayerDataStore dataStore;
    private final AuditService audit;
    private final GameDesignService gameDesign;
    private final ConcurrentHashMap<UUID, Wallet> wallets = new ConcurrentHashMap<>();

    public CreditService(IdlePlugin plugin, Database database, PlayerDataStore dataStore,
                         AuditService audit, GameDesignService gameDesign) {
        this.plugin = plugin;
        this.database = database;
        this.dataStore = dataStore;
        this.audit = audit;
        this.gameDesign = gameDesign;
    }

    /**
     * Forgets a cached wallet. Called on join and quit so a player arriving
     * from another backend never reads a snapshot this server took earlier.
     */
    public void invalidate(UUID owner) {
        wallets.remove(owner);
    }

    public long balance(UUID owner) {
        return current(owner).credits();
    }

    public long seasonOffset(UUID owner) {
        return current(owner).seasonOffset();
    }

    /**
     * Runs on the payout tick for every online player, so it stays off the
     * main thread entirely: the write is queued, and because it is relative it
     * needs no prior read to be correct.
     */
    public void recordCoinsEarned(UUID owner, long amount) {
        if (amount <= 0) return;
        String season = gameDesign.seasonId();
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection()) {
                ensureRow(connection, owner, season);
                rollSeasonIfStale(connection, owner, season);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE idle_credit_wallet "
                                + "SET season_coins_earned = season_coins_earned + ? "
                                + "WHERE owner_uuid = ?")) {
                    update.setLong(1, amount);
                    update.setString(2, owner.toString());
                    update.executeUpdate();
                }
                invalidate(owner);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to record season earnings for "
                        + owner + ": " + e.getMessage());
            }
        });
    }

    /**
     * Idempotent payment-provider/admin adjustment — the entry point a Discord
     * top-up bot should use. Replaying the same transaction id cannot mint
     * Credits twice: {@code transaction_id} is the ledger's primary key, so the
     * duplicate INSERT aborts the whole transaction and nothing is granted.
     */
    public boolean adjust(UUID owner, long amount, String type,
                          String transactionId, String detail) {
        if (transactionId == null || transactionId.isBlank() || amount == 0) return false;
        String season = gameDesign.seasonId();
        boolean committed = database.executeTransaction("Credit adjustment " + transactionId, connection -> {
            ensureRow(connection, owner, season);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idle_credit_ledger "
                            + "(transaction_id, owner_uuid, entry_type, amount, detail_json) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, transactionId);
                insert.setString(2, owner.toString());
                insert.setString(3, type);
                insert.setLong(4, amount);
                insert.setString(5, detail);
                insert.executeUpdate();
            }
            // The balance floor lives in the WHERE clause so a deduction can
            // never overdraw, however stale this server's view was.
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_credit_wallet SET credits = credits + ? "
                            + "WHERE owner_uuid = ? AND credits + ? >= 0")) {
                update.setLong(1, amount);
                update.setString(2, owner.toString());
                update.setLong(3, amount);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Insufficient Credits or missing wallet row");
                }
            }
        });
        // Invalidated either way: a rejection usually means the real balance
        // has moved on without us, so the cached copy is the last thing to
        // keep trusting.
        invalidate(owner);
        if (!committed) return false;
        audit.log(owner, "CREDIT_" + type, "{\"transaction\":\"" + safe(transactionId)
                + "\",\"amount\":" + amount + "}");
        return true;
    }

    /**
     * Pays a qualifying fixed-result Coin checkout. At least 85% remains
     * payable in Coins; seasonal offset is additionally bounded by 30,000
     * Coins and 25% of Coins earned.
     */
    public Checkout hybridPay(UUID owner, long coinPrice, long requestedCredits,
                              String purchaseId, String idempotencyKey) {
        if (coinPrice <= 0) return Checkout.fail("Invalid checkout price.");
        PlayerData player = dataStore.getOnline(owner);
        if (player == null) return Checkout.fail("Player data is not loaded.");
        Wallet wallet = current(owner);
        long ratio = plugin.getConfig().getLong("credits.coin-offset-per-credit", 20);
        long maxPercent = plugin.getConfig().getLong("credits.max-offset-percent", 15);
        long seasonalHardCap = plugin.getConfig().getLong("credits.season-offset-cap", 30_000);
        long byPurchase = coinPrice * maxPercent / 100;
        long byEarned = wallet.seasonCoinsEarned() / 4;
        long seasonRemaining = Math.max(0,
                Math.min(seasonalHardCap, byEarned) - wallet.seasonOffset());
        long maxOffset = Math.min(byPurchase, seasonRemaining);
        long credits = Math.min(Math.max(0, requestedCredits),
                Math.min(wallet.credits(), maxOffset / Math.max(1, ratio)));
        long offset = credits * ratio;
        long coins = coinPrice - offset;
        if (player.getBalance() < coins) return Checkout.fail("Not enough Coins.");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Checkout.fail("Checkout requires an idempotency key.");
        }

        String tx = "HYBRID:" + idempotencyKey;
        double balanceAfter = player.getBalance() - coins;
        String detail = "{\"purchase\":\"" + safe(purchaseId) + "\",\"coinOffset\":" + offset
                + ",\"coins\":" + coins + "}";
        boolean committed = database.executeTransaction("Hybrid checkout " + tx, connection -> {
            // The immutable row is also the replay guard when zero Credits
            // are used; every checkout is idempotent, not only Credit spends.
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idle_credit_ledger "
                            + "(transaction_id, owner_uuid, entry_type, amount, detail_json) "
                            + "VALUES (?, ?, 'CHECKOUT', ?, ?)")) {
                insert.setString(1, tx);
                insert.setString(2, owner.toString());
                insert.setLong(3, -credits);
                insert.setString(4, detail);
                insert.executeUpdate();
            }
            // Re-checking the Credit balance here is what makes the quote
            // above safe to compute from a cached wallet: if a grant or spend
            // landed elsewhere in between, this fails instead of overdrawing.
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_credit_wallet SET credits = credits - ?, "
                            + "season_coin_offset = season_coin_offset + ? "
                            + "WHERE owner_uuid = ? AND credits >= ?")) {
                update.setLong(1, credits);
                update.setLong(2, offset);
                update.setString(3, owner.toString());
                update.setLong(4, credits);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Credit balance changed during checkout");
                }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE idle_players SET balance = ? WHERE uuid = ?")) {
                update.setDouble(1, balanceAfter);
                update.setString(2, owner.toString());
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Player wallet row is missing");
                }
            }
        });
        if (!committed) {
            return Checkout.fail("Checkout was already processed or the Credit balance changed.");
        }
        player.addBalance(-coins);
        invalidate(owner);
        audit.log(owner, "HYBRID_PAY", "{\"purchase\":\"" + safe(purchaseId)
                + "\",\"coins\":" + coins + ",\"credits\":" + credits + "}");
        return new Checkout(true, "Paid " + coins + " Coins + " + credits + " Credits.", coins, credits);
    }

    public List<LedgerEntry> historySync(UUID owner, int limit) {
        List<LedgerEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT transaction_id, entry_type, amount, detail_json, created_at "
                             + "FROM idle_credit_ledger WHERE owner_uuid = ? "
                             + "ORDER BY created_at DESC LIMIT ?")) {
            select.setString(1, owner.toString());
            select.setInt(2, Math.max(1, Math.min(100, limit)));
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    entries.add(new LedgerEntry(rs.getString("transaction_id"),
                            rs.getString("entry_type"), rs.getLong("amount"),
                            rs.getString("detail_json"), String.valueOf(rs.getTimestamp("created_at"))));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load Credit history: " + e.getMessage());
        }
        return entries;
    }

    // ---- wallet reads ----

    /**
     * Cached read-through. The cache exists to keep menu redraws off the
     * database; it is dropped by {@link #invalidate} after every write and on
     * every login, so it can go stale but can never be written back.
     */
    private Wallet current(UUID owner) {
        Wallet cached = wallets.get(owner);
        if (cached != null && cached.season().equals(gameDesign.seasonId())) {
            return cached;
        }
        Wallet wallet = loadSync(owner);
        if (!wallet.season().equals(gameDesign.seasonId())) {
            wallet = rollSeason(owner);
        }
        wallets.put(owner, wallet);
        return wallet;
    }

    /** Reads the wallet, creating an empty row the first time a player is seen. */
    private Wallet loadSync(UUID owner) {
        String season = gameDesign.seasonId();
        try (Connection connection = database.getConnection()) {
            Wallet wallet = read(connection, owner);
            if (wallet != null) {
                return wallet;
            }
            ensureRow(connection, owner, season);
            // Another server may have inserted first, so read back rather than
            // assuming the zeroes we just tried to write.
            Wallet created = read(connection, owner);
            return created != null ? created : new Wallet(0, season, 0, 0);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load Credit wallet for " + owner + ": " + e.getMessage());
            // A zero wallet fails closed: purchases are refused, nothing is
            // granted, and no write is derived from this value.
            return new Wallet(0, season, 0, 0);
        }
    }

    private Wallet read(Connection connection, UUID owner) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_WALLET)) {
            select.setString(1, owner.toString());
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Wallet(rs.getLong("credits"), rs.getString("season_id"),
                        rs.getLong("season_coin_offset"), rs.getLong("season_coins_earned"));
            }
        }
    }

    /**
     * Creates the wallet row if this player has never had one. Safe to race
     * with another backend doing the same thing.
     */
    private void ensureRow(Connection connection, UUID owner, String season) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                (database.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE")
                        + " INTO idle_credit_wallet (owner_uuid, credits, season_id, "
                        + "season_coin_offset, season_coins_earned) VALUES (?, 0, ?, 0, 0)")) {
            insert.setString(1, owner.toString());
            insert.setString(2, season);
            insert.executeUpdate();
        }
    }

    /**
     * Clears seasonal offset accounting when the season has rolled over.
     * Credits themselves survive a season — they were paid for. The season
     * check is in the WHERE clause, so two backends rolling the same wallet
     * cannot double-clear a season's earnings.
     */
    private void rollSeasonIfStale(Connection connection, UUID owner, String season)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE idle_credit_wallet SET season_id = ?, season_coin_offset = 0, "
                        + "season_coins_earned = 0 WHERE owner_uuid = ? AND season_id <> ?")) {
            update.setString(1, season);
            update.setString(2, owner.toString());
            update.setString(3, season);
            update.executeUpdate();
        }
    }

    private Wallet rollSeason(UUID owner) {
        String season = gameDesign.seasonId();
        database.executeTransaction("Credit season roll " + owner,
                connection -> rollSeasonIfStale(connection, owner, season));
        return loadSync(owner);
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
