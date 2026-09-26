package com.fittrack.api;

import com.fittrack.app.CompositeDtos.*;
import com.fittrack.app.CompositeService;
import com.fittrack.app.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * Composite write endpoints.
 *
 * <p>An optional {@code Idempotency-Key} header lets an offline client retry the same logical
 * operation safely: a repeat submission returns the original aggregate instead of creating a
 * second one. Requests without the header behave exactly as before.
 */
@RestController
@RequestMapping("/api/v1")
public class CompositeController {
    private final CompositeService service;
    private final IdempotencyService idempotency;
    public CompositeController(CompositeService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/workout-sessions/complete")
    public CompositeResponse completeSession(@Valid @RequestBody WorkoutSessionCompleteRequest request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @AuthenticationPrincipal String user) {
        return replay(key, user, "workout_session", () -> service.completeSession(request, user));
    }

    @PostMapping("/workout-templates/complete")
    public CompositeResponse completeTemplate(@Valid @RequestBody WorkoutTemplateCompleteRequest request,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @AuthenticationPrincipal String user) {
        return replay(key, user, "workout_template", () -> service.completeTemplate(request, user));
    }

    @PostMapping("/meals/complete")
    public CompositeResponse completeMeal(@Valid @RequestBody MealCompleteRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                          @AuthenticationPrincipal String user) {
        return replay(key, user, "meal", () -> service.completeMeal(request, user));
    }

    /**
     * Runs a composite write at most once per (user, idempotency key).
     *
     * <p>A prior result for the same key is returned verbatim, so a retried offline operation
     * cannot duplicate an aggregate. The ledger is scoped to the authenticated user.
     */
    private CompositeResponse replay(String key, String principal, String resource,
                                     java.util.function.Supplier<CompositeResponse> action) {
        UUID user = UUID.fromString(principal);
        IdempotencyService.Prior prior = idempotency.find(user, key);
        if (prior != null && prior.resultId() != null) {
            return new CompositeResponse(prior.resultId(), resource, prior.childCount());
        }
        CompositeResponse created = action.get();
        idempotency.record(user, key, resource, created.id(), created.childCount());
        return created;
    }
}
