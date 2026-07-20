package dev.branzx.idlefarm.service.design;

import java.util.UUID;

/** Credits Coins to a player's runtime balance. */
@FunctionalInterface
public interface CoinSink {
    void add(UUID owner, double amount);
}
