package dev.branzx.idle.economy;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.storage.PlayerDataStore;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;

/**
 * Exposes Idle Coins to the rest of the server as the Vault economy.
 *
 * <p>This plugin owns the balance rather than mirroring someone else's,
 * because charging is woven into the gameplay transactions: a claim, merge or
 * hire debits {@code idle_players.balance} inside the same SQL transaction
 * that writes the node or worker row, so a failure can never leave a player
 * charged for nothing. An external economy could not participate in that
 * transaction, so owning the currency is what keeps the guarantee.
 *
 * <p>Every operation here routes through {@link PlayerDataStore}, which knows
 * whether the player is online on this backend (cache is authoritative) or
 * anywhere else (relative UPDATE against the shared database). Nothing in this
 * class reads a balance and writes it back.
 *
 * <p>Banks are not supported: there is no account model behind them here, and
 * reporting {@code NOT_IMPLEMENTED} is how Vault expects that to be said.
 */
public final class IdleEconomy implements Economy {

    private final IdlePlugin plugin;
    private final PlayerDataStore dataStore;

    public IdleEconomy(IdlePlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    // ---- identity ----

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return plugin.getName();
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    /** Balances are stored as a double but presented as whole Coins. */
    @Override
    public int fractionalDigits() {
        return 0;
    }

    @Override
    public String format(double amount) {
        return Math.round(amount) + " " + currencyNamePlural();
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getConfig().getString("currency-name", "Coins");
    }

    @Override
    public String currencyNameSingular() {
        return currencyNamePlural();
    }

    // ---- accounts ----

    private UUID id(OfflinePlayer player) {
        return player == null ? null : player.getUniqueId();
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer byName(String playerName) {
        return plugin.getServer().getOfflinePlayer(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        UUID uuid = id(player);
        return uuid != null && dataStore.accountExists(uuid);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        UUID uuid = id(player);
        return uuid != null && dataStore.createAccount(uuid, player.getName());
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    // ---- balances ----

    @Override
    public double getBalance(OfflinePlayer player) {
        UUID uuid = id(player);
        return uuid == null ? 0 : dataStore.balanceOf(uuid);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        UUID uuid = id(player);
        if (uuid == null) {
            return failure(0, "Unknown player");
        }
        if (amount < 0) {
            return failure(dataStore.balanceOf(uuid), "Cannot withdraw a negative amount");
        }
        if (!dataStore.withdraw(uuid, amount)) {
            return failure(dataStore.balanceOf(uuid), "Insufficient " + currencyNamePlural());
        }
        return new EconomyResponse(amount, dataStore.balanceOf(uuid),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        UUID uuid = id(player);
        if (uuid == null) {
            return failure(0, "Unknown player");
        }
        if (amount < 0) {
            return failure(dataStore.balanceOf(uuid), "Cannot deposit a negative amount");
        }
        if (!dataStore.deposit(uuid, amount)) {
            return failure(dataStore.balanceOf(uuid), "No account for this player");
        }
        return new EconomyResponse(amount, dataStore.balanceOf(uuid),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    private EconomyResponse failure(double balance, String message) {
        return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "Idle does not support bank accounts");
    }

    // ---- deprecated name-keyed overloads ----
    //
    // Vault still routes older callers through these. Resolving to an
    // OfflinePlayer keeps one implementation of the actual logic.

    @Override
    public boolean hasAccount(String playerName) {
        return hasAccount(byName(playerName));
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(byName(playerName));
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(byName(playerName));
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(byName(playerName));
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(byName(playerName), amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(byName(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(byName(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(byName(playerName), amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(byName(playerName));
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(byName(playerName));
    }

    // ---- banks (unsupported) ----

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}
