package dev.branzx.idlefarm.service;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.Database;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;

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
 */
public final class CreditService {

    public record Checkout(boolean success, String message, long coinsCharged, long creditsCharged) {
        static Checkout fail(String message) { return new Checkout(false, message, 0, 0); }
    }

    public record LedgerEntry(String transactionId, String type, long amount,
                              String detail, String createdAt) {
    }

    private record Wallet(long credits, String season, long seasonOffset, long seasonCoinsEarned) {
        Wallet credits(long value) { return new Wallet(value, season, seasonOffset, seasonCoinsEarned); }
        Wallet offset(long value) { return new Wallet(credits, season, value, seasonCoinsEarned); }
        Wallet earned(long value) { return new Wallet(credits, season, seasonOffset, value); }
        Wallet reset(String value) { return new Wallet(credits, value, 0, 0); }
    }

    private final IdleFarmPlugin plugin;
    private final Database database;
    private final PlayerDataStore dataStore;
    private final AuditService audit;
    private final GameDesignService gameDesign;
    private final ConcurrentHashMap<UUID, Wallet> wallets = new ConcurrentHashMap<>();

    public CreditService(IdleFarmPlugin plugin, Database database, PlayerDataStore dataStore,
                         AuditService audit, GameDesignService gameDesign) {
        this.plugin = plugin;
        this.database = database;
        this.dataStore = dataStore;
        this.audit = audit;
        this.gameDesign = gameDesign;
    }

    public void loadAllSync() {
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT owner_uuid, credits, season_id, season_coin_offset, season_coins_earned "
                             + "FROM idlefarm_credit_wallet");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                wallets.put(UUID.fromString(rs.getString("owner_uuid")),
                        new Wallet(rs.getLong("credits"), rs.getString("season_id"),
                                rs.getLong("season_coin_offset"), rs.getLong("season_coins_earned")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load Credit wallets: " + e.getMessage());
        }
    }

    public long balance(UUID owner) {
        return current(owner).credits();
    }

    public long seasonOffset(UUID owner) {
        return current(owner).seasonOffset();
    }

    public void recordCoinsEarned(UUID owner, long amount) {
        if (amount <= 0) return;
        Wallet wallet = current(owner);
        wallet = wallet.earned(wallet.seasonCoinsEarned() + amount);
        wallets.put(owner, wallet);
        persist(owner, wallet);
    }

    /**
     * Idempotent payment-provider/admin adjustment. Replaying the same
     * transaction id cannot mint Credits twice.
     */
    public boolean adjust(UUID owner, long amount, String type, String transactionId, String detail) {
        if (transactionId == null || transactionId.isBlank() || amount == 0) return false;
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO idlefarm_credit_ledger "
                            + "(transaction_id, owner_uuid, entry_type, amount, detail_json) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, transactionId);
                insert.setString(2, owner.toString());
                insert.setString(3, type);
                insert.setLong(4, amount);
                insert.setString(5, detail);
                insert.executeUpdate();
            }
            Wallet before = current(owner);
            long next = Math.max(0, before.credits() + amount);
            if (amount < 0 && before.credits() + amount < 0) {
                connection.rollback();
                return false;
            }
            Wallet after = before.credits(next);
            try (PreparedStatement upsert = connection.prepareStatement(
                    "REPLACE INTO idlefarm_credit_wallet "
                            + "(owner_uuid, credits, season_id, season_coin_offset, season_coins_earned) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                bindWallet(upsert, owner, after);
                upsert.executeUpdate();
            }
            connection.commit();
            wallets.put(owner, after);
            audit.log(owner, "CREDIT_" + type, "{\"transaction\":\"" + safe(transactionId)
                    + "\",\"amount\":" + amount + "}");
            return true;
        } catch (SQLException e) {
            // Duplicate transaction IDs are the expected idempotent replay path.
            return false;
        }
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

        String tx = "HYBRID:" + idempotencyKey;
        if (credits > 0 && !adjust(owner, -credits, "SPEND", tx,
                "{\"purchase\":\"" + safe(purchaseId) + "\",\"coinOffset\":" + offset + "}")) {
            return Checkout.fail("Checkout was already processed or the Credit balance changed.");
        }
        player.addBalance(-coins);
        Wallet after = current(owner).offset(current(owner).seasonOffset() + offset);
        wallets.put(owner, after);
        persist(owner, after);
        audit.log(owner, "HYBRID_PAY", "{\"purchase\":\"" + safe(purchaseId)
                + "\",\"coins\":" + coins + ",\"credits\":" + credits + "}");
        return new Checkout(true, "Paid " + coins + " Coins + " + credits + " Credits.", coins, credits);
    }

    public List<LedgerEntry> historySync(UUID owner, int limit) {
        List<LedgerEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT transaction_id, entry_type, amount, detail_json, created_at "
                             + "FROM idlefarm_credit_ledger WHERE owner_uuid = ? "
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

    private Wallet current(UUID owner) {
        Wallet wallet = wallets.computeIfAbsent(owner,
                ignored -> new Wallet(0, gameDesign.seasonId(), 0, 0));
        if (!wallet.season().equals(gameDesign.seasonId())) {
            wallet = wallet.reset(gameDesign.seasonId());
            wallets.put(owner, wallet);
            persist(owner, wallet);
        }
        return wallet;
    }

    private void persist(UUID owner, Wallet wallet) {
        database.submitWrite(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement upsert = connection.prepareStatement(
                         "REPLACE INTO idlefarm_credit_wallet "
                                 + "(owner_uuid, credits, season_id, season_coin_offset, season_coins_earned) "
                                 + "VALUES (?, ?, ?, ?, ?)")) {
                bindWallet(upsert, owner, wallet);
                upsert.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to persist Credit wallet: " + e.getMessage());
            }
        });
    }

    private void bindWallet(PreparedStatement statement, UUID owner, Wallet wallet) throws SQLException {
        statement.setString(1, owner.toString());
        statement.setLong(2, wallet.credits());
        statement.setString(3, wallet.season());
        statement.setLong(4, wallet.seasonOffset());
        statement.setLong(5, wallet.seasonCoinsEarned());
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
