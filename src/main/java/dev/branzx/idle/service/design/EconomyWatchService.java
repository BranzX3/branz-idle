package dev.branzx.idle.service.design;

import dev.branzx.idle.service.design.TelemetryService.AdminAlert;
import dev.branzx.idle.service.design.TelemetryService.Severity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Turns the economy dashboard's threshold alerts into pushed admin
 * notifications.
 *
 * <p>The dashboard already decides what counts as an outlier, but it only ever
 * answered an admin who opened the metrics screen, so a breach that started at
 * 3am was found whenever someone happened to look. This sweeps on a timer and
 * reports the transitions instead of the state: an alert is announced when it
 * is first raised and once more when it clears, so a threshold that stays
 * breached for a week does not reprint every sweep.</p>
 *
 * <p>Severity changes are treated as a new alert, because a warning becoming
 * critical is exactly the moment an operator wants to hear about again.</p>
 */
public final class EconomyWatchService {

    /** Where raised and cleared alerts go. Injected to stay headless-testable. */
    public interface AlertSink {
        void raised(AdminAlert alert);

        void cleared(AdminAlert alert);
    }

    /** The dashboard's synthetic "everything is fine" row; never broadcast. */
    private static final String HEALTHY = "HEALTHY";

    private final Supplier<List<AdminAlert>> source;
    private final AlertSink sink;
    // code -> the alert as last announced, so repeat sweeps stay silent
    private final Map<String, AdminAlert> raised = new LinkedHashMap<>();

    public EconomyWatchService(Supplier<List<AdminAlert>> source, AlertSink sink) {
        this.source = source;
        this.sink = sink;
    }

    /**
     * Evaluates the current thresholds and reports what changed. Reads the
     * database through the supplier, so call this off the main thread.
     */
    public synchronized void sweep() {
        Map<String, AdminAlert> current = new LinkedHashMap<>();
        for (AdminAlert alert : source.get()) {
            if (HEALTHY.equals(alert.code()) || alert.severity() == Severity.INFO) continue;
            current.put(alert.code(), alert);
        }
        for (AdminAlert alert : current.values()) {
            AdminAlert previous = raised.get(alert.code());
            if (previous == null || previous.severity() != alert.severity()) {
                sink.raised(alert);
            }
        }
        for (AdminAlert alert : List.copyOf(raised.values())) {
            if (!current.containsKey(alert.code())) sink.cleared(alert);
        }
        raised.clear();
        raised.putAll(current);
    }

    /** Codes currently considered breached; admin surfaces and tests only. */
    public synchronized List<String> activeCodes() {
        return List.copyOf(raised.keySet());
    }
}
