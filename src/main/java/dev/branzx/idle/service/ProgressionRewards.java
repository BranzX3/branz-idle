package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;

/**
 * Tunable progression reward catalog. Keeping reward values out of gameplay
 * branches prevents UI text, simulations and settlement logic from drifting
 * apart when the Balance Bible is revised.
 */
public record ProgressionRewards(
        long starterNodeExp,
        long firstCollectionExp,
        long firstExpeditionExp,
        long extraExpeditionExp,
        int extraExpeditionsPerDay,
        long focusCommissionExp,
        long behaviorCommissionCoins,
        int supplyCommissionChroniclePoints,
        long weeklyChapterExp,
        long weeklyChapterCoins,
        long catchupCommissionExp) {

    public static ProgressionRewards from(IdlePlugin plugin) {
        String root = "progression.rewards.";
        return new ProgressionRewards(
                plugin.getConfig().getLong(root + "starter-node-exp", 300),
                plugin.getConfig().getLong(root + "first-collection-exp", 100),
                plugin.getConfig().getLong(root + "first-expedition-exp", 700),
                plugin.getConfig().getLong(root + "extra-expedition-exp", 100),
                plugin.getConfig().getInt(root + "extra-expeditions-per-day", 3),
                plugin.getConfig().getLong(root + "focus-commission-exp", 400),
                plugin.getConfig().getLong(root + "behavior-commission-coins", 600),
                plugin.getConfig().getInt(root + "supply-commission-chronicle-points", 3),
                plugin.getConfig().getLong(root + "weekly-chapter-exp", 3_500),
                plugin.getConfig().getLong(root + "weekly-chapter-coins", 2_000),
                plugin.getConfig().getLong(root + "catchup-commission-exp", 200));
    }
}
