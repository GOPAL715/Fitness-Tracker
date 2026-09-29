package com.fittrack.push;

import com.fittrack.reminder.NotificationDeliveryProvider;
import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Web Push delivery over VAPID (RFC 8291 encryption, RFC 8292 authentication).
 *
 * <p>Implements the existing {@link NotificationDeliveryProvider} contract unchanged. It does not
 * decide what is due, retry, or record state: {@link com.fittrack.reminder.ReminderDeliveryService}
 * stays the only owner of the delivery state machine, and the three outcomes returned here are exactly
 * the three that contract already defines.
 *
 * <h2>Fan-out</h2>
 * A user may have several devices. One attempt fans out to every subscription that user owns, and
 * succeeds if <em>any</em> of them accepted the message: the reminder arrived, which is what the
 * occurrence asked for. A subscription that has permanently gone is removed and does not spoil the
 * attempt for the others. Only a failure affecting every subscription is reported as a failure.
 *
 * <h2>Failure classification</h2>
 * Only a response that positively proves a subscription is unusable is permanent. Everything else -
 * including any status this code does not recognise - is temporary, because a permanent verdict makes
 * the service close the occurrence with no retry. Guessing wrong in that direction silently drops a
 * user's reminder, so the default is deliberately "try again".
 */
@Component
public class WebPushDeliveryProvider implements NotificationDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(WebPushDeliveryProvider.class);

    /**
     * How long one push attempt may take.
     *
     * <p>Bounded so a hung push service cannot stall the reminder scheduler's single thread. A
     * timeout is reported as temporary, which is correct: nothing was learned about the subscription.
     */
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(10);

    private final PushSubscriptionService subscriptions;
    private final PushProperties properties;

    public WebPushDeliveryProvider(PushSubscriptionService subscriptions, PushProperties properties) {
        this.subscriptions = subscriptions;
        this.properties = properties;
    }

    /**
     * Reports which state the subsystem booted in.
     *
     * <p>Log names the reason push is unusable without naming any key material, so an operator can
     * tell "switched off" from "misconfigured" from the log alone.
     */
    @PostConstruct
    void reportConfiguration() {
        if (!properties.isEnabled()) {
            log.info("web_push_disabled reason=app_push_enabled_false");
        } else if (!properties.getVapid().isComplete()) {
            log.info("web_push_disabled reason=incomplete_vapid_configuration missing={}", missingParts());
        } else {
            // The subject is operator contact information supplied by the deployment, never a user's
            // address, so it is safe to log. Neither key is ever logged, in any form.
            log.info("web_push_ready channel=web-push subject={}", properties.getVapid().getSubject());
        }
    }

    private String missingParts() {
        StringBuilder missing = new StringBuilder();
        if (isBlank(properties.getVapid().getSubject())) missing.append("subject ");
        if (isBlank(properties.getVapid().getPublicKey())) missing.append("public-key ");
        if (isBlank(properties.getVapid().getPrivateKey())) missing.append("private-key");
        return missing.toString().trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Whether this instance can actually push: the switch plus a complete VAPID triple. */
    public boolean usable() {
        return properties.isEnabled() && properties.getVapid().isComplete();
    }

    @Override
    public String channel() { return "web-push"; }

    @Override
    public Outcome deliver(DeliveryRequest request) {
        if (!usable()) {
            // Push off or misconfigured. The placeholder provider's existing answer applies, so the
            // occurrence closes exactly as it did before Phase 13 rather than being retried forever
            // against a channel that does not exist.
            log.info("web_push_skipped reminder_id={} reason=push_not_configured", request.reminderId());
            return Outcome.PERMANENT_FAILURE;
        }

        List<PushSubscriptionService.DeliverableSubscription> targets =
                subscriptions.deliverableForUser(request.userId());
        if (targets.isEmpty()) {
            log.info("web_push_no_subscriptions reminder_id={} user_id={}", request.reminderId(), request.userId());
            return Outcome.PERMANENT_FAILURE;
        }

        PushService pushService = new PushService();
        try {
            pushService.setPublicKey(nl.martijndwars.webpush.Utils.loadPublicKey(
                    properties.getVapid().getPublicKey()));
            pushService.setPrivateKey(nl.martijndwars.webpush.Utils.loadPrivateKey(
                    properties.getVapid().getPrivateKey()));
            pushService.setSubject(properties.getVapid().getSubject());
        } catch (Exception e) {
            // A malformed or mismatched key pair is a configuration fault, not a per-recipient one.
            // Refusing loudly here is what stops a bad deployment from appearing to work.
            log.error("web_push_configuration_invalid exception_type={}", e.getClass().getSimpleName());
            return Outcome.PERMANENT_FAILURE;
        }

        // Deliberately minimal. The title and message are the reminder's own text, which the user
        // wrote for themselves. No health values, weights, meals, tokens or record identifiers travel
        // in a push payload, because a notification lands on a lock screen anyone nearby can read.
        String payload = buildPayload(request);

        int delivered = 0;
        int temporaryFailures = 0;
        int permanentFailures = 0;

        for (PushSubscriptionService.DeliverableSubscription target : targets) {
            switch (send(pushService, payload, target)) {
                case DELIVERED -> delivered++;
                case PERMANENT_FAILURE -> {
                    permanentFailures++;
                    // The push service has said this subscription can never receive again. Keeping the
                    // row would mean retrying a dead endpoint on every future reminder forever.
                    subscriptions.deleteByEndpoint(target.endpoint());
                    log.info("web_push_subscription_removed reason=expired_or_invalid"
                            + " subscription_id={} host={}", target.id(), hostOf(target.endpoint()));
                }
                default -> temporaryFailures++;
            }
        }

        if (delivered > 0) {
            log.info("web_push_delivered reminder_id={} delivered={} permanent={} temporary={}",
                    request.reminderId(), delivered, permanentFailures, temporaryFailures);
            return Outcome.DELIVERED;
        }
        if (temporaryFailures > 0) {
            log.warn("web_push_temporary_failure reminder_id={} subscriptions={}",
                    request.reminderId(), targets.size());
            return Outcome.TEMPORARY_FAILURE;
        }
        log.warn("web_push_all_subscriptions_invalid reminder_id={} removed={}",
                request.reminderId(), permanentFailures);
        return Outcome.PERMANENT_FAILURE;
    }

    /**
     * Sends to one subscription and classifies the result.
     *
     * <p>Only the endpoint's host is ever logged. The endpoint path contains a per-installation
     * secret, and the subscription keys are the decrypting half of the subscription, so neither
     * appears in any log line.
     */
    private Outcome send(PushService pushService, String payload,
            PushSubscriptionService.DeliverableSubscription target) {
        try {
            Subscription subscription = new Subscription(target.endpoint(),
                    new Subscription.Keys(target.p256dh(), target.authSecret()));
            // A reminder is not urgent news, so a normal-priority delivery lets the push service
            // coalesce under load rather than dropping a burst. Urgency is a constructor argument in
            // this library, not a setter.
            Notification notification = new Notification(subscription, payload,
                    nl.martijndwars.webpush.Urgency.NORMAL);

            // The send is synchronous, so the attempt timeout is applied to this thread. A hung push
            // service must not stall the reminder scheduler's only thread indefinitely.
            ExecutorService caller = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "web-push-attempt");
                t.setDaemon(true);
                return t;
            });
            try {
                // The call is made reflectively and the result captured untyped. Both are necessary:
                // web-push's send() returns Apache HttpResponse and declares jose4j exceptions, and
                // those are runtime-scoped dependencies of that library, so they are absent from this
                // compile classpath even though they are present at run time. Naming either - directly
                // or through inference - is a compile error, and no dependency exclusion is added to
                // work around it. The status is read reflectively from the captured response.
                RawResult captured = new RawResult();
                Future<?> pending = caller.submit(() -> {
                    captured.response = PushService.class
                            .getMethod("send", Notification.class)
                            .invoke(pushService, notification);
                    return null;
                });
                pending.get(ATTEMPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                return classify(statusOf(captured.response));
            } finally {
                caller.shutdownNow();
            }
        } catch (TimeoutException e) {
            return Outcome.TEMPORARY_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.TEMPORARY_FAILURE;
        } catch (Exception e) {
            // A send that never completed says nothing about the subscription's validity. Some push
            // services also use a connection failure to signal a dead endpoint, so this stays
            // temporary and the occurrence is retried rather than closed.
            log.warn("web_push_send_error subscription_id={} exception_type={}",
                    target.id(), e.getClass().getSimpleName());
            return Outcome.TEMPORARY_FAILURE;
        }
    }

    /**
     * Reads the HTTP status from the push library's response without naming its type.
     *
     * <p>web-push returns an Apache {@code HttpResponse}, and its Apache dependency is runtime-scoped,
     * so the class is present when this runs but absent when this compiles. Reflection over the
     * two accessors keeps the compile classpath honest. A response that cannot be read is reported as
     * unknown, which classifies as temporary - never as a verdict that the subscription is dead.
     */
    private static int statusOf(Object response) {
        try {
            Object statusLine = response.getClass().getMethod("getStatusLine").invoke(response);
            Object code = statusLine.getClass().getMethod("getStatusCode").invoke(statusLine);
            return ((Number) code).intValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** Carries the push library's response out of the sending thread without naming its type. */
    private static final class RawResult {
        private Object response;
    }

    /**
     * Maps a push service response to an outcome.
     *
     * <p>Only statuses that positively mean "this subscription is gone" are permanent. Anything
     * unrecognised, including 2xx variants and any 4xx not listed, is temporary.
     */
    static Outcome classify(int status) {
        // 404 and 410 are the two the push specifications define for a removed or expired
        // subscription, reported after a service worker is unregistered or its key rotates out.
        if (status == 404 || status == 410) return Outcome.PERMANENT_FAILURE;
        // 429 is explicit rate limiting: not delivered, but the subscription is perfectly valid.
        if (status == 429) return Outcome.TEMPORARY_FAILURE;
        // 5xx is the push service's own fault, not the subscription's.
        if (status >= 500) return Outcome.TEMPORARY_FAILURE;
        if (status >= 200 && status < 300) return Outcome.DELIVERED;
        // 401/403 mean this application's VAPID key was rejected - a deployment fault affecting every
        // subscription equally. Retried rather than treated as permanent, so the operator sees it and
        // no subscription is deleted on the strength of a server-side configuration mistake.
        return Outcome.TEMPORARY_FAILURE;
    }

    /**
     * The minimal notification body.
     *
     * <p>Reminder text and identity only. The client re-fetches anything else behind its existing
     * authenticated session, so nothing sensitive is ever at rest on a lock screen or a push service.
     */
    private String buildPayload(DeliveryRequest request) {
        return "{\"type\":\"reminder\""
                + ",\"title\":" + json(request.title())
                + ",\"body\":" + json(request.message())
                + ",\"reminderId\":" + json(request.reminderId() == null ? null : request.reminderId().toString())
                + ",\"occurrenceAt\":" + json(request.occurrence() == null ? null : request.occurrence().toString())
                + "}";
    }

    /** Minimal JSON string escaping. Without it a quote or newline in a reminder title breaks the payload. */
    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String hostOf(String endpoint) {
        try {
            return java.net.URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
