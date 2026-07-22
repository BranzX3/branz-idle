package dev.branzx.idle.service.design;

import dev.branzx.idle.service.design.TelemetryService.AdminAlert;
import dev.branzx.idle.service.design.TelemetryService.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EconomyWatchServiceTest {

    private final List<AdminAlert> dashboard = new ArrayList<>();
    private final List<String> raised = new ArrayList<>();
    private final List<String> cleared = new ArrayList<>();

    private final EconomyWatchService watch = new EconomyWatchService(() -> List.copyOf(dashboard),
            new EconomyWatchService.AlertSink() {
                @Override public void raised(AdminAlert alert) {
                    raised.add(alert.code() + ":" + alert.severity());
                }

                @Override public void cleared(AdminAlert alert) {
                    cleared.add(alert.code());
                }
            });

    @Test
    void anOngoingBreachIsAnnouncedOnceAndItsRecoveryOnce() {
        dashboard.add(new AdminAlert(Severity.WARNING, "LOW_SINK_RATIO", "7d sink ratio 4%"));

        watch.sweep();
        watch.sweep();
        watch.sweep();

        // A threshold breached for a week must not reprint every sweep.
        assertEquals(List.of("LOW_SINK_RATIO:WARNING"), raised);
        assertEquals(List.of(), cleared);
        assertEquals(List.of("LOW_SINK_RATIO"), watch.activeCodes());

        dashboard.clear();
        watch.sweep();
        watch.sweep();

        assertEquals(List.of("LOW_SINK_RATIO"), cleared);
        assertEquals(List.of(), watch.activeCodes());
    }

    @Test
    void aBreachThatReturnsIsAnnouncedAgain() {
        AdminAlert alert = new AdminAlert(Severity.CRITICAL, "COIN_SUPPLY", "Total Coins exceed cap");
        dashboard.add(alert);
        watch.sweep();
        dashboard.clear();
        watch.sweep();
        dashboard.add(alert);
        watch.sweep();

        assertEquals(List.of("COIN_SUPPLY:CRITICAL", "COIN_SUPPLY:CRITICAL"), raised);
        assertEquals(List.of("COIN_SUPPLY"), cleared);
    }

    @Test
    void escalatingSeverityIsAnnouncedAgainButDeescalationIsNotSilentEither() {
        dashboard.add(new AdminAlert(Severity.WARNING, "PLAYER_BALANCE", "richest 11m"));
        watch.sweep();
        dashboard.clear();
        dashboard.add(new AdminAlert(Severity.CRITICAL, "PLAYER_BALANCE", "richest 90m"));
        watch.sweep();

        assertEquals(List.of("PLAYER_BALANCE:WARNING", "PLAYER_BALANCE:CRITICAL"), raised);
    }

    @Test
    void theHealthyPlaceholderIsNeverBroadcast() {
        dashboard.add(new AdminAlert(Severity.INFO, "HEALTHY", "No economy threshold is breached"));

        watch.sweep();

        assertEquals(List.of(), raised);
        assertEquals(List.of(), watch.activeCodes());
    }
}
