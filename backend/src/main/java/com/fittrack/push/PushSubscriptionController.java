package com.fittrack.push;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated browser push subscription management.
 *
 * <p>The user identity always comes from the validated JWT principal, never from the request body.
 * A client that sends a {@code user_id} is simply ignored on that field, so one user cannot attach a
 * subscription to another account.
 *
 * <p>Responses carry only the endpoint and timestamps. The p256dh key and auth secret are written on
 * registration and never read back, so no route here can leak them.
 */
@RestController
@RequestMapping("/api/v1/push")
public class PushSubscriptionController {

    private final PushSubscriptionService subscriptions;
    private final PushProperties properties;

    public PushSubscriptionController(PushSubscriptionService subscriptions, PushProperties properties) {
        this.subscriptions = subscriptions;
        this.properties = properties;
    }

    /**
     * The configuration a browser needs in order to subscribe.
     *
     * <p>Only the public key is returned. The private key stays on the server, and {@code enabled}
     * lets the client explain "reminders aren't available yet" instead of failing opaquely at
     * subscribe time.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "enabled", properties.isEnabled() && properties.getVapid().isComplete(),
                "publicKey", properties.getVapid().getPublicKey() == null
                        ? "" : properties.getVapid().getPublicKey());
    }

    /** The caller's own subscriptions. Never another user's. */
    @GetMapping("/subscriptions")
    public List<Map<String, Object>> list(@AuthenticationPrincipal String principal) {
        return subscriptions.forUser(uuid(principal)).stream()
                .map(subscription -> Map.<String, Object>of(
                        "id", subscription.id().toString(),
                        "endpoint", subscription.endpoint(),
                        "created_at", subscription.createdAt().toString(),
                        "updated_at", subscription.updatedAt().toString()))
                .toList();
    }

    /**
     * Registers or refreshes a subscription.
     *
     * <p>Idempotent: the same endpoint registered again refreshes its keys instead of creating a
     * second row, which is what a browser does on every page load.
     */
    @PostMapping("/subscriptions")
    public Map<String, Object> register(@RequestBody Map<String, Object> body,
            @AuthenticationPrincipal String principal) {
        UUID userId = uuid(principal);
        String endpoint = validatedEndpoint(body.get("endpoint"));
        String p256dh = string(body.get("p256dh"));
        String auth = string(body.get("auth"));
        try {
            PushSubscriptionService.validateKeys(p256dh, auth);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        PushSubscriptionService.PushSubscription saved = subscriptions.register(userId, endpoint, p256dh, auth);
        return Map.of("id", saved.id().toString(), "endpoint", saved.endpoint());
    }

    /**
     * Removes one of the caller's subscriptions.
     *
     * <p>Scoped to the caller, so another user's endpoint is not reachable from here. Absence is a
     * success, because a browser unsubscribes locally before the server is told.
     */
    @DeleteMapping("/subscriptions")
    public Map<String, Object> delete(@RequestParam String endpoint,
            @AuthenticationPrincipal String principal) {
        UUID userId = uuid(principal);
        String validated;
        try {
            validated = PushSubscriptionService.validateEndpoint(endpoint);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        boolean removed = subscriptions.delete(userId, validated);
        return Map.of("removed", removed);
    }

    private String validatedEndpoint(Object value) {
        try {
            return PushSubscriptionService.validateEndpoint(string(value));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID uuid(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
