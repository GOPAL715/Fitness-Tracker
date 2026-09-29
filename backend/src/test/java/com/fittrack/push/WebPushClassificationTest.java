package com.fittrack.push;

import com.fittrack.reminder.NotificationDeliveryProvider.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failure classification and input validation, with no push service involved.
 *
 * <p>Classification is the part of this feature most likely to lose a user's reminder if it is wrong.
 * A permanent verdict closes an occurrence with no retry, so anything uncertain must be temporary.
 * The "unrecognised" case below exists specifically to pin that conservative default.
 */
class WebPushClassificationTest {

    @ParameterizedTest(name = "HTTP {0} is a permanent failure")
    @ValueSource(ints = {404, 410})
    @DisplayName("Phase 13 push - a subscription the service reports as gone is permanent")
    void goneSubscriptionIsPermanent(int status) {
        assertThat(WebPushDeliveryProvider.classify(status))
                .as("the push service has said this subscription can never receive again")
                .isEqualTo(Outcome.PERMANENT_FAILURE);
    }

    @ParameterizedTest(name = "HTTP {0} is temporary")
    @ValueSource(ints = {429, 500, 502, 503, 504})
    @DisplayName("Phase 13 push - rate limiting and server faults are temporary")
    void transientResponseIsTemporary(int status) {
        assertThat(WebPushDeliveryProvider.classify(status))
                .as("the subscription is valid; the service or its capacity is not")
                .isEqualTo(Outcome.TEMPORARY_FAILURE);
    }

    @ParameterizedTest(name = "HTTP {0} is a delivery")
    @ValueSource(ints = {200, 201, 202, 204})
    @DisplayName("Phase 13 push - any success is a delivery")
    void successIsDelivered(int status) {
        assertThat(WebPushDeliveryProvider.classify(status)).isEqualTo(Outcome.DELIVERED);
    }

    @ParameterizedTest(name = "HTTP {0} is not treated as permanent")
    @ValueSource(ints = {400, 401, 403, 408, 0, -1, 999})
    @DisplayName("Phase 13 push - an unrecognised status never deletes a subscription")
    void unrecognisedStatusIsNotPermanent(int status) {
        // 401/403 mean this server's VAPID key was rejected - a deployment fault affecting every user.
        // Closing occurrences and deleting subscriptions over a configuration mistake would be the worst
        // possible outcome, so only a positively dead subscription is permanent.
        assertThat(WebPushDeliveryProvider.classify(status))
                .as("only 404 and 410 prove a subscription is gone")
                .isEqualTo(Outcome.TEMPORARY_FAILURE);
    }


    // ------------------------------------------------------------- endpoints

    @Test
    @DisplayName("Phase 13 push - a real push service endpoint is accepted")
    void acceptsRealPushServices() {
        assertThat(PushSubscriptionService.validateEndpoint(
                "https://fcm.googleapis.com/fcm/send/abc123")).endsWith("abc123");
        assertThat(PushSubscriptionService.validateEndpoint(
                "https://updates.push.services.mozilla.com/wpush/v2/xyz")).contains("mozilla");
        assertThat(PushSubscriptionService.validateEndpoint(
                "https://web.push.apple.com/abc")).contains("apple");
    }

    @Test
    @DisplayName("Phase 13 push - an endpoint aimed at the server itself is refused")
    void refusesInternalEndpoints() {
        // The endpoint is a URL the server then requests, so an unfiltered value is an SSRF sink.
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("http://localhost:8080/admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("https://127.0.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("https://169.254.169.254/latest"))
                .as("cloud metadata is the classic SSRF target")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("https://10.0.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("https://192.168.1.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("https://172.16.0.1/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Phase 13 push - a non-push or non-https endpoint is refused")
    void refusesNonPushEndpoints() {
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("http://fcm.googleapis.com/x"))
                .as("plaintext would expose the request headers")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("https://evil.example.com/x"))
                .as("an arbitrary host would let a client aim the server anywhere")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateEndpoint(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Phase 13 push - malformed subscription keys are refused at registration")
    void refusesMalformedKeys() {
        String validPoint = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
        String validSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

        assertThatThrownBy(() -> PushSubscriptionService.validateKeys("not base64!!", validSecret))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateKeys(validPoint, "short"))
                .as("the auth secret is exactly 16 bytes per RFC 8291")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PushSubscriptionService.validateKeys(null, validSecret))
                .isInstanceOf(IllegalArgumentException.class);

        // A base64 value of the right length must still be accepted, or real browsers would be locked out.
        PushSubscriptionService.validateKeys(validPoint, validSecret);
    }
}