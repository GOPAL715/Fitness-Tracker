package com.fittrack.acceptance.support;

import com.fittrack.health.HealthProvider;
import com.fittrack.health.HealthProviderException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for a wearable provider.
 *
 * <p>Returns scripted records and can simulate timeout, 5xx, auth failure, malformed data and a
 * partial batch. No network, no credentials.
 */
public class FakeHealthProvider implements HealthProvider {

    public enum Mode {
        NORMAL, TIMEOUT, SERVER_ERROR, AUTH_FAILURE, MALFORMED, PARTIAL, THROWING
    }

    private final List<ActivityRecord> activity = new ArrayList<>();
    private final List<BodyRecord> body = new ArrayList<>();
    private final List<String> windows = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.NORMAL;
    private volatile String cursor;

    public void reset() {
        activity.clear();
        body.clear();
        windows.clear();
        calls.set(0);
        mode = Mode.NORMAL;
        cursor = null;
    }

    public void use(Mode selected) { this.mode = selected; }

    public void addActivity(String recordId, LocalDate date, int steps) {
        activity.add(new ActivityRecord(recordId, date, steps, 30, 200));
    }

    public void addBody(String recordId, LocalDate date, String weightLb) {
        body.add(new BodyRecord(recordId, date, new BigDecimal(weightLb), new BigDecimal("20")));
    }

    /** Canned records that the provider will return on every fetch. */
    public void setRecords(List<ActivityRecord> activities, List<BodyRecord> bodies) {
        activity.clear();
        body.clear();
        activity.addAll(activities);
        body.addAll(bodies);
    }

    @Override
    public Batch fetch(LocalDate from, LocalDate to, String resumeToken) {
        calls.incrementAndGet();
        windows.add(from + ".." + to);
        switch (mode) {
            case TIMEOUT:
                throw new HealthProviderException("upstream timeout for " + SECRET, HealthProvider.CATEGORY_TIMEOUT);
            case SERVER_ERROR:
                throw new HealthProviderException("upstream 503 for " + SECRET, HealthProvider.CATEGORY_UNAVAILABLE);
            case AUTH_FAILURE:
                throw new HealthProviderException("token rejected " + SECRET, HealthProvider.CATEGORY_AUTH);
            case MALFORMED:
                throw new HealthProviderException("unparseable payload " + SECRET, HealthProvider.CATEGORY_MALFORMED);
            case THROWING:
                throw new IllegalStateException("provider exploded " + SECRET);
            case PARTIAL:
                // Only the first record arrives, standing in for a truncated response.
                return new Batch(activity.isEmpty() ? List.of() : activity.subList(0, 1),
                        List.of(), to.toString());
            default:
                return new Batch(activity, body, to.toString());
        }
    }

    @Override
    public String key() { return "fake-wearable"; }

    public int callCount() { return calls.get(); }

    /** Window bounds requested, so incremental behavior can be asserted. */
    public List<String> windows() { return List.copyOf(windows); }

    /** Marker that must never appear in any client-facing response. */
    public static final String SECRET = "vapid-fake-health-SECRET-DO-NOT-LEAK";
}
