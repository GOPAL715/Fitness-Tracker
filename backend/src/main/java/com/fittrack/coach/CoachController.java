package com.fittrack.coach;

import com.fittrack.ai.AiProvider;
import com.fittrack.ai.AiProviderException;
import com.fittrack.ai.AiUsageService;

import com.fittrack.app.IdempotencyService;
import com.fittrack.coach.CoachDtos.CoachInsightPayload;
import com.fittrack.coach.CoachDtos.CoachInsightRequest;
import com.fittrack.coach.CoachDtos.CoachInsightResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Phase 9 AI Coach.
 *
 * <p>One endpoint, one request, one response. There is no conversation, no memory and no history:
 * nothing about a Coach answer is persisted, and the response carries no user identifier, no raw
 * context and no provider payload.
 *
 * <p>Ownership is resolved exclusively from the authenticated principal. The request body has no
 * {@code user_id} field and none is read, so a caller cannot widen their own visibility by posting
 * one.
 */
@RestController
@RequestMapping("/api/v1/coach")
public class CoachController {

    /**
     * One feature key for every Coach request.
     *
     * <p>The AI quota is keyed by feature, so giving each Coach operation its own key would grant
     * each one a fresh per-minute and per-day budget. A single {@code coach} key keeps the
     * effective ceiling fixed however the endpoint is later subdivided.
     */
    private static final String FEATURE = "coach";

    /** A replayed idempotency key is refused rather than silently re-billed. */
    static final String REPLAY_CODE = "coach_request_already_processed";

    private final CoachContextService contextService;
    private final CoachPromptBuilder prompts;
    private final CoachInsightParser parser;
    private final AiProvider provider;
    private final AiUsageService usage;
    private final IdempotencyService idempotency;

    public CoachController(CoachContextService contextService, CoachPromptBuilder prompts,
            CoachInsightParser parser, AiProvider provider, AiUsageService usage,
            IdempotencyService idempotency) {
        this.contextService = contextService;
        this.prompts = prompts;
        this.parser = parser;
        this.provider = provider;
        this.usage = usage;
        this.idempotency = idempotency;
    }

    @PostMapping("/insights")
    public CoachInsightResponse insights(@Valid @RequestBody(required = false) CoachInsightRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @AuthenticationPrincipal String principal) {

        UUID user = owner(principal);
        CoachInsightRequest body = request == null ? new CoachInsightRequest(null, null) : request;

        // A blank-but-present question is a client error, not an empty question. Silently treating
        // it as "no question" would hide a frontend bug.
        if (body.question() != null && body.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must not be blank");
        }
        int window = body.window();
        if (!CoachDtos.ALLOWED_WINDOW_DAYS.contains(window)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window_days must be 7, 30 or 90");
        }

        // Replay protection. The key is claimed atomically, so two concurrent duplicates cannot both
        // reach the provider: exactly one insert wins the row and the loser is refused even while
        // the winner is still in flight. A read-then-write would not be safe here, because the
        // ledger's record path resolves a conflict silently rather than reporting it.
        //
        // The previous answer is deliberately not reconstructed. Coach output is generated text with
        // no persisted form, and storing it to enable replay would mean persisting a response body,
        // which Phase 9 explicitly rules out.
        if (key != null && !key.isBlank() && !idempotency.claim(user, key, FEATURE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, REPLAY_CODE);
        }

        String contextJson = contextService.buildContextJson(user, window);
        List<AiProvider.CoachMessage> messages = prompts.build(body.askedQuestion(), contextJson)
                .stream()
                .map(m -> new AiProvider.CoachMessage(m.role(), m.content()))
                .toList();

        // AiUsageService takes the principal as issued, and resolves the owner itself.
        AiUsageService.Attempt attempt;
        try {
            attempt = usage.start(principal, FEATURE);
        } catch (AiUsageService.QuotaExceededException e) {
            usage.quotaRejected(principal, FEATURE, "quota");
            // Rethrown rather than converted. A quota refusal must keep its own
            // "ai_quota_exceeded" code, so it stays distinguishable from a transport rate limit,
            // which is a different layer with a different remedy.
            throw e;
        }

        try {
            AiProvider.CoachAnalysis result = provider.analyzeCoachMessages(messages);
            CoachInsightPayload payload = parser.parse(result.text());
            usage.success(attempt, result.usage(), result.model());
            return CoachInsightResponse.from(payload, result.model(), requestId());
        } catch (CoachInsightParser.InvalidCoachResponseException e) {
            // The provider answered, but not with something usable. It is accounted as a failure
            // with a malformed category, and the raw payload is never surfaced.
            usage.failure(attempt, new AiProviderException("provider response failed validation", "malformed"), null);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider unavailable");
        } catch (RuntimeException e) {
            usage.failure(attempt, e, null);
            throw e;
        }
    }

    /**
     * Resolves the owner from the authenticated principal alone.
     *
     * <p>Parsed explicitly rather than cast inside the SQL, so a malformed principal produces a
     * clean refusal rather than a database cast error surfacing as a 500.
     */
    static UUID owner(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.security.access.AccessDeniedException("Not permitted");
        }
    }

    /** The same correlation id that appears in the error envelope and in server logs. */
    private static String requestId() {
        return MDC.get("request_id");
    }
}
