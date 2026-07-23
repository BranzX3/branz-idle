package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.wallet.api.LedgerEntry;
import dev.branzx.wallet.api.WalletApi;

import java.util.List;
import java.util.UUID;

/**
 * Adapter onto the central {@link WalletApi}. Credit used to live in Idle; it is
 * now owned by the BranzWallet plugin so every server system shares one
 * authoritative balance. This class keeps Idle's original method surface so
 * callers (admin grants, the payout tick, menus, the connection listener) did
 * not have to change — it just forwards to the shared service.
 *
 * <p>The {@link WalletApi} handle is null when BranzWallet is not installed.
 * Every method degrades safely in that case (zero balance, no-op grant), which
 * matches how callers already guarded a null {@code creditService}.
 */
public final class CreditService {

    private final IdlePlugin plugin;
    private final WalletApi wallet;

    public CreditService(IdlePlugin plugin, WalletApi wallet) {
        this.plugin = plugin;
        this.wallet = wallet;
    }

    /** True when the central wallet is available to serve Credit operations. */
    public boolean isAvailable() {
        return wallet != null;
    }

    public long balance(UUID owner) {
        return wallet == null ? 0 : wallet.credits(owner);
    }

    /**
     * Idempotent grant/deduction, forwarded to the shared ledger. Replaying the
     * same {@code transactionId} still cannot mint Credits twice — that guard
     * now lives in BranzWallet.
     */
    public boolean adjust(UUID owner, long amount, String type,
                          String transactionId, String detail) {
        return wallet != null && wallet.adjustCredit(owner, amount, type, transactionId, detail);
    }

    /** Feeds the seasonal Hybrid Pay offset cap; called from the payout tick. */
    public void recordCoinsEarned(UUID owner, long amount) {
        if (wallet != null) {
            wallet.recordCoinsEarned(owner, amount);
        }
    }

    /** Recent Credit ledger entries, newest first. Empty when unavailable. */
    public List<LedgerEntry> historySync(UUID owner, int limit) {
        return wallet == null ? List.of() : wallet.creditHistory(owner, limit);
    }

    /** Drops the wallet's cached snapshot for {@code owner} (join/quit). */
    public void invalidate(UUID owner) {
        if (wallet != null) {
            wallet.invalidateCredit(owner);
        }
    }
}
