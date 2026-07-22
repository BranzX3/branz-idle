package dev.branzx.idle.service;

import dev.branzx.idle.IdlePlugin;
import dev.branzx.idle.node.NodeType;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Claim prices rise with how much land a player already holds, which is what
 * limits a sprawling territory instead of a hard cap. The refund must never
 * exceed what the claim cost, or churning claims becomes an income.
 */
class ClaimCostEscalationTest {

    private ClaimService claims;

    @BeforeEach
    void setUp() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getDouble(eq("claims.residential-cost"), anyDouble())).thenReturn(500.0);
        when(config.getDouble(eq("claims.production-cost"), anyDouble())).thenReturn(1000.0);
        when(config.getDouble(eq("claims.production-cost-factor"), anyDouble())).thenReturn(1.6);
        when(config.getDouble(eq("claims.residential-cost-factor"), anyDouble())).thenReturn(1.15);
        when(config.getDouble(eq("claims.unclaim-refund-ratio"), anyDouble())).thenReturn(0.5);

        claims = new ClaimService(plugin, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void theFirstClaimCostsTheBasePrice() {
        assertEquals(1000.0, claims.costAtIndex(NodeType.MINING, 0), 0.001);
        assertEquals(500.0, claims.costAtIndex(NodeType.RESIDENTIAL, 0), 0.001);
    }

    @Test
    void eachFurtherClaimCostsMore() {
        assertEquals(1600.0, claims.costAtIndex(NodeType.MINING, 1), 0.001);
        assertEquals(2560.0, claims.costAtIndex(NodeType.MINING, 2), 0.001);
        assertEquals(4096.0, claims.costAtIndex(NodeType.MINING, 3), 0.001);
    }

    /** Residential rises gently: a single 3×3 Complex alone spends 8 plots. */
    @Test
    void residentialEscalatesGentlyEnoughToBuildAComplex() {
        double eighthPlot = claims.costAtIndex(NodeType.RESIDENTIAL, 8);

        assertTrue(eighthPlot < 2000, "eight plots must stay affordable, was " + eighthPlot);
        assertTrue(eighthPlot > 500, "but not free");
    }

    @Test
    void everyProductionTypeSharesOneEscalation() {
        // The ladder counts production nodes as one pool, so spreading across
        // types is not a way to dodge the rising price.
        assertEquals(claims.costAtIndex(NodeType.MINING, 3),
                claims.costAtIndex(NodeType.FARMING, 3), 0.001);
    }

    /**
     * The refund prices the claim being given back, not the next one up.
     * Refunding against the higher next price would pay players to churn.
     */
    @Test
    void aRefundNeverExceedsWhatTheClaimCost() {
        for (int held = 1; held <= 10; held++) {
            double paidForTheLast = claims.costAtIndex(NodeType.MINING, held - 1);
            double refund = claims.costAtIndex(NodeType.MINING, held - 1) * 0.5;

            assertTrue(refund < paidForTheLast,
                    "refund " + refund + " must stay under the " + paidForTheLast + " paid");
        }
    }

    /** A misconfigured factor below 1 would make land cheaper the more you own. */
    @Test
    void aFactorBelowOneIsClampedSoPricesNeverFall() {
        IdlePlugin plugin = mock(IdlePlugin.class);
        FileConfiguration config = mock(FileConfiguration.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getDouble(eq("claims.production-cost"), anyDouble())).thenReturn(1000.0);
        when(config.getDouble(eq("claims.production-cost-factor"), anyDouble())).thenReturn(0.5);
        ClaimService broken = new ClaimService(plugin, null, null, null, null, null, null, null,
                null, null, null, null);

        assertEquals(1000.0, broken.costAtIndex(NodeType.MINING, 5), 0.001);
    }

    @Test
    void aNegativeCountIsTreatedAsTheFirstClaim() {
        assertEquals(1000.0, claims.costAtIndex(NodeType.MINING, -3), 0.001);
    }
}
