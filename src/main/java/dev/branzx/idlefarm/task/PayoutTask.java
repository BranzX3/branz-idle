package dev.branzx.idlefarm.task;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.service.BoosterService;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public final class PayoutTask extends BukkitRunnable {

    private final IdleFarmPlugin plugin;
    private final PlayerDataStore dataStore;
    private BoosterService boosterService;
    private dev.branzx.idlefarm.service.CreditService creditService;

    public PayoutTask(IdleFarmPlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    public void setBoosterService(BoosterService boosterService) {
        this.boosterService = boosterService;
    }

    public void setCreditService(dev.branzx.idlefarm.service.CreditService creditService) {
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
    }

    private String formatAmount(double amount) {
        if (amount == Math.floor(amount)) {
            return String.valueOf((long) amount);
        }
        return String.format("%.2f", amount);
    }
}
