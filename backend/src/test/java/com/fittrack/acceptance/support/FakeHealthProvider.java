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
    private volatile String currentDevice;
    private final java.util.Map<String, String> bodyDevice = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, String> activityDevice = new java.util.LinkedHashMap<>();
    private int pageSize = Integer.MAX_VALUE;
    private boolean repeatingCursor;

    public void reset() {
        activity.clear();
        body.clear();
        windows.clear();
        calls.set(0);
        mode = Mode.NORMAL;
        cursor = null;
        // Device scoping is per-test state: leaving it set would hide records from the next test.
        activityDevice.clear();
        bodyDevice.clear();
        currentDevice = null;
    }

    public void use(Mode selected) { this.mode = selected; }

    public void addActivity(String recordId, LocalDate date, int steps) {
        activity.add(new ActivityRecord(recordId, date, steps, 30, 200));
    }

    /**
     * A record only the tagged device's sync may see.
     *
     * <p>A real provider returns one account's data per credential, so a second connected device is a
     * second account and never sees the first one's records. Without this, syncing device A would
     * import device B's rows under device A's provenance, which no real integration would do.
     */
    public void addActivityFor(String recordId, String deviceTag, LocalDate date, int steps) {
        activity.add(new ActivityRecord(recordId, date, steps, 30, 200));
        activityDevice.put(recordId, deviceTag);
    }

    public void addBodyFor(String recordId, String deviceTag, LocalDate date, String weightLb) {
        body.add(new BodyRecord(recordId, date, new BigDecimal(weightLb), new BigDecimal("20")));
        bodyDevice.put(recordId, deviceTag);
    }

    /**
     * Restricts subsequent fetches to one device tag. Passing null makes every record visible
     * again, which is what the single-device tests rely on.
     */
    public void onlyForDevice(String deviceTag) { this.currentDevice = deviceTag; }

    private boolean visible(java.util.Map<String, String> owners, String recordId) {
        String owner = owners.get(recordId);
        return owner == null || owner.equals(currentDevice);
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
                // Only the first record arrives, standing in for a truncated response, and the
                // sequence ends. The sync still stores the day watermark, so the next pass re-reads
                // the same window and picks up the records this one never delivered.
                return new Batch(activity.isEmpty() ? List.of() : activity.subList(0, 1),
                        List.of(), null);
            default:
                return nextPage(resumeToken);
        }
    }

    /**
     * Pages the scripted records and advances an offset cursor.
     *
     * <p>The fake previously returned the whole record set on every call with a cursor equal to the
     * window end. That modelled an infinite feed: now that the sync loop genuinely follows cursors,
     * such a provider would loop forever and every sync would double-count. A real provider stops
     * by returning a null cursor on its last page, and that is what this does.
     */
    private Batch nextPage(String resumeToken) {
        if (repeatingCursor && resumeToken != null) {
            // A provider that hands back the cursor it was just given. The sync loop must detect the
            // repeat rather than fetch forever, so the records it returns are ignored downstream.
            return new Batch(List.of(), List.of(), resumeToken);
        }
        int offset = 0;
        if (resumeToken != null && resumeToken.startsWith("offset:")) {
            try {
                offset = Integer.parseInt(resumeToken.substring("offset:".length()));
            } catch (RuntimeException e) {
                offset = 0;
            }
        }
        List<ActivityRecord> pageActivity = slice(activity, offset, pageSize,
                r -> visible(activityDevice, r.recordId()));
        List<BodyRecord> pageBody = slice(body, offset, pageSize,
                r -> visible(bodyDevice, r.recordId()));
        int consumed = offset + Math.max(pageActivity.size(), pageBody.size());
        int total = Math.max(activity.size(), body.size());
        return new Batch(pageActivity, pageBody, consumed < total ? "offset:" + consumed : null);
    }

    /**
     * Slices a page, counting only records visible to the current device.
     *
     * <p>Visibility is applied while counting, so an invisible record does not consume page budget
     * and a device is not handed a short page merely because another device owns some rows.
     */
    private static <T> List<T> slice(List<T> all, int offset, int size, java.util.function.Predicate<T> keep) {
        List<T> selected = new java.util.ArrayList<>();
        int seen = 0;
        for (T record : all) {
            if (!keep.test(record)) continue;
            if (seen++ < offset) continue;
            selected.add(record);
            if (selected.size() >= size) break;
        }
        return List.copyOf(selected);
    }

    /** Records per page. Everything fits on one page by default. */
    public void setPageSize(int size) { this.pageSize = Math.max(1, size); }

    /** Makes the provider echo back a cursor it was just given, to exercise cycle detection. */
    public void useRepeatingCursor(boolean repeat) { this.repeatingCursor = repeat; }

    @Override
    public String key() { return "fake-wearable"; }

    public int callCount() { return calls.get(); }

    /** Window bounds requested, so incremental behavior can be asserted. */
    public List<String> windows() { return List.copyOf(windows); }

    /** Marker that must never appear in any client-facing response. */
    public static final String SECRET = "vapid-fake-health-SECRET-DO-NOT-LEAK";
}
