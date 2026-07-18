package dev.branzx.idlefarm.task;

import dev.branzx.idlefarm.IdleFarmPlugin;
import dev.branzx.idlefarm.storage.PlayerData;
import dev.branzx.idlefarm.storage.PlayerDataStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public final class PayoutTask extends BukkitRunnable {

    private final IdleFarmPlugin plugin;
    private final PlayerDataStore dataStore;

    public PayoutTask(IdleFarmPlugin plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
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

            double payout = money * multiplier;
            data.addBalance(payout);
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
