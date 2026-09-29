package com.fittrack.reminder;

import com.fittrack.reminder.NotificationPreferencesService.Preferences;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's own notification preferences.
 *
 * <p>There is no path parameter and no identifier in the body: the user is the authenticated
 * principal, always. A client that posts {@code user_id} has it ignored, so one account cannot write
 * another's preferences - the same property the push subscription route already has.
 *
 * <p>Responses carry booleans, a wall-clock window and a zone. No push endpoint, subscription key,
 * browser permission or provider detail is read here, so none can be exposed.
 */
@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferencesController {

    private final NotificationPreferencesService preferences;

    public NotificationPreferencesController(NotificationPreferencesService preferences) {
        this.preferences = preferences;
    }

    /**
     * The caller's preferences, or the defaults when they have never been set.
     *
     * <p>Read-only: this never creates a row.
     */
    @GetMapping
    public Map<String, Object> get(@AuthenticationPrincipal String principal) {
        return render(preferences.forUser(uuid(principal)));
    }

    /**
     * Replaces the caller's preferences.
     *
     * <p>Validation is strict and rejects rather than repairs. The response is re-read from the
     * database rather than echoed from the request, so what the client is told is what was actually
     * stored - including the canonicalised zone id.
     */
    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body,
            @AuthenticationPrincipal String principal) {
        UUID userId = uuid(principal);
        Preferences validated;
        try {
            validated = NotificationPreferencesService.validate(
                    bool(body, "pushEnabled"),
                    bool(body, "reminderNotificationsEnabled"),
                    bool(body, "quietHoursEnabled"),
                    text(body, "quietHoursStart"),
                    text(body, "quietHoursEnd"),
                    text(body, "timezone"));
        } catch (IllegalArgumentException e) {
            // The message names the field only; it never echoes a stored value back.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return render(preferences.save(userId, validated));
    }

    /**
     * The wire shape.
     *
     * <p>Times are {@code HH:mm} rather than the API's usual {@code HH:mm:ss}: these are wall-clock
     * times a person types into a time input, and seconds are not a value the user ever set.
     */
    private static Map<String, Object> render(Preferences preferences) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pushEnabled", preferences.pushEnabled());
        body.put("reminderNotificationsEnabled", preferences.reminderNotificationsEnabled());
        body.put("quietHoursEnabled", preferences.quietHoursEnabled());
        body.put("quietHoursStart", preferences.quietHoursStart() == null
                ? null : preferences.quietHoursStart().format(DateTimeFormatter.ofPattern("HH:mm")));
        body.put("quietHoursEnd", preferences.quietHoursEnd() == null
                ? null : preferences.quietHoursEnd().format(DateTimeFormatter.ofPattern("HH:mm")));
        // Null means "not set", which is exactly what the UI must show rather than substituting UTC.
        body.put("timezone", preferences.timezone());
        return body;
    }

    /** Only a real boolean is accepted; a string or number is refused rather than coerced. */
    private static Boolean bool(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        throw new IllegalArgumentException(field + " must be true or false");
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body.get(field);
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
