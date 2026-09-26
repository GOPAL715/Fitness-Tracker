package com.fittrack.acceptance.support;

import com.fittrack.reminder.NotificationDeliveryProvider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic stand-in for the reminder delivery channel.
 *
 * <p>No push credentials, no network. Every attempt is recorded so tests can assert not just the
 * outcome but how many times the channel was actually invoked.
 */
public class FakeNotificationProvider implements NotificationDeliveryProvider {

    public enum Mode {
        DELIVERED, TEMPORARY_FAILURE, PERMANENT_FAILURE, THROWING
    }

    private final List<DeliveryRequest> attempts = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Mode mode = Mode.DELIVERED;

    /** Number of leading attempts that fail temporarily before succeeding. */
    private volatile int temporaryFailuresBeforeSuccess = 0;

    public void reset() {
        mode = Mode.DELIVERED;
        temporaryFailuresBeforeSuccess = 0;
        calls.set(0);
        attempts.clear();
    }

    public void use(Mode selected) { this.mode = selected; }

    /** Fails {@code count} times temporarily, then delivers. */
    public void failTemporarilyThenSucceed(int count) {
        this.temporaryFailuresBeforeSuccess = count;
        this.mode = Mode.DELIVERED;
    }

    @Override
    public Outcome deliver(DeliveryRequest request) {
        calls.incrementAndGet();
        attempts.add(request);
        if (mode == Mode.THROWING) throw new IllegalStateException("push channel exploded");
        if (mode == Mode.PERMANENT_FAILURE) return Outcome.PERMANENT_FAILURE;
        if (mode == Mode.TEMPORARY_FAILURE) return Outcome.TEMPORARY_FAILURE;
        if (temporaryFailuresBeforeSuccess > 0 && calls.get() <= temporaryFailuresBeforeSuccess) {
            return Outcome.TEMPORARY_FAILURE;
        }
        return Outcome.DELIVERED;
    }

    @Override
    public String channel() { return "fake-push"; }

    public int callCount() { return calls.get(); }

    public List<DeliveryRequest> attempts() { return List.copyOf(attempts); }

    /** Occurrences this channel actually received, for duplicate-delivery assertions. */
    public List<UUID> deliveredReminderIds() {
        return attempts.stream().map(DeliveryRequest::reminderId).toList();
    }
}
