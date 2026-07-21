package dev.branzx.idle.task;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.service.BoosterService;
import dev.branzx.idle.storage.PlayerData;
import dev.branzx.idle.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public final class PayoutTask extends BukkitRunnable {

    private final IdlePlugin plugin;
    private final PlayerDataStore dataStore;
    private final BoosterService boosterService;
    private final dev.branzx.idle.service.CreditService creditService;

    public PayoutTask(IdlePlugin plugin, PlayerDataStore dataStore,
                      BoosterService boosterService,
                      dev.branzx.idle.service.CreditService creditService) {
        this.plugin = plugin;
        this.dataStore = dataStore;
        this.boosterService = boosterService;
        this.creditService = creditService;
    }

    @Override
    public void run() {
        double money = plugin.getConfig().getDouble("rewards.money", 10.0);
        int exp = plugin.getConfig().getInt("rewards.exp", 5);
        double multiplier = plugin.getConfig().getDouble("afk-bonus-multiplier", 1.0);
        long intervalSeconds = plugin.getConfig().getLong("payout-interval-seconds", 60);
        String currencyName = plugin.getConfig().getString("currency-name", "Coins");

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerData data = dataStore.getOnline(player.getUniqueId());
            if (data == null) {
                continue;
            }

            double boost = boosterService == null ? 1.0
                    : boosterService.multiplier(player.getUniqueId(), BoosterService.MONEY);
            double payout = money * multiplier * boost;
            data.addBalance(payout);
            if (creditService != null) {
                creditService.recordCoinsEarned(player.getUniqueId(), (long) Math.floor(payout));
            }
            data.incrementOnlineMinutes(Math.max(1, intervalSeconds / 60));
            player.giveExp(exp);

            player.sendActionBar(Component.text(
                    "+" + formatAmount(payout) + " " + currencyName + "  +" + exp + " EXP",
                    NamedTextColor.GREEN));
        }
        // Bound crash-loss exposure to one payout interval instead of the
        // whole login session. Saves are ordered and remain off the main
        // server thread.
        dataStore.saveAllDirtyAsync();
    }

    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }
}
