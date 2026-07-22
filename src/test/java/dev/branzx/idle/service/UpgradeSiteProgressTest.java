package dev.branzx.idle.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The progress readout is derived from the deadline, so it must stay sane for
 * a deadline that has passed, a clock that jumped, or a misconfigured build
 * time — the display keeps ticking until the upgrade is actually collected.
 */
class UpgradeSiteProgressTest {

    @Test
    void progressRunsFromZeroToOneHundred() {
        assertEquals(0, UpgradeSiteService.percentComplete(60_000, 60_000));
        assertEquals(50, UpgradeSiteService.percentComplete(30_000, 60_000));
        assertEquals(100, UpgradeSiteService.percentComplete(0, 60_000));
    }

    @Test
    void anOverdueUpgradeReadsComplete() {
        // The completion tick waits for a loaded chunk, so "remaining" can go
        // negative for a long time on an offline player's node.
        assertEquals(100, UpgradeSiteService.percentComplete(-500_000, 60_000));
    }

    @Test
    void remainingLongerThanTheTotalClampsToZero() {
        // Survives a config change that shortens build time mid-upgrade.
        assertEquals(0, UpgradeSiteService.percentComplete(900_000, 60_000));
    }

    @Test
    void aZeroOrNegativeDurationReportsCompleteRatherThanDividing() {
        assertEquals(100, UpgradeSiteService.percentComplete(1_000, 0));
        assertEquals(100, UpgradeSiteService.percentComplete(1_000, -5));
    }

    @Test
    void barTracksThePercentageAndKeepsAFixedWidth() {
        assertEquals("··········", UpgradeSiteService.bar(0));
        assertEquals("▬▬▬▬▬·····", UpgradeSiteService.bar(50));
        assertEquals("▬▬▬▬▬▬▬▬▬▬", UpgradeSiteService.bar(100));
        assertEquals(10, UpgradeSiteService.bar(37).codePointCount(0,
                UpgradeSiteService.bar(37).length()));
    }
}
