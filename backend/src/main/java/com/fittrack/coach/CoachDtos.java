package com.fittrack.coach;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Phase 9 Coach request and response contracts.
 *
 * <p>The Coach is a single-request, single-response endpoint. There is no conversation, no
 * memory and no history: nothing here is persisted, and the response deliberately carries no
 * user identifier, no raw context and no provider payload.
 *
 * <p>Request validation is enforced by bean validation on {@link CoachInsightRequest}. The
 * response contract is enforced by {@link CoachInsightResponse}, which only the validated
 * provider parser can construct.
 */
public final class CoachDtos {

    private CoachDtos() {
    }

    /** The only context window the Coach accepts. A larger window is a deliberate future change. */
    public static final List<Integer> ALLOWED_WINDOW_DAYS = List.of(7, 30, 90);

    /** Matches the existing weekly analytics framing and the smallest useful context. */
    public static final int DEFAULT_WINDOW_DAYS = 7;

    /**
     * The request body.
     *
     * <p>There is no {@code user_id} field and none is ever read: the owner is resolved
     * exclusively from the authenticated principal. A caller that posts one is ignored rather
     * than honoured, so a body field can never widen visibility.
     *
     * <p>{@code question} is optional and untrusted. It is carried as data to the provider and is
     * never concatenated into a system instruction.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CoachInsightRequest(
            /** Optional free-text question. Untrusted; never treated as an instruction. */
            @Size(max = 500, message = "question must be at most 500 characters") String question,
            /** One of 7, 30 or 90. Null means {@link #DEFAULT_WINDOW_DAYS}. */
            Integer windowDays) {

        /** The effective window, never null. */
        public int window() {
            return windowDays == null ? DEFAULT_WINDOW_DAYS : windowDays;
        }

        /** The question, or null. A blank-but-present question is rejected by the controller. */
        public String askedQuestion() {
            return question == null || question.isBlank() ? null : question.trim();
        }
    }

    /**
     * The validated provider response.
     *
     * <p>Only {@link com.fittrack.coach.CoachInsightParser} constructs one. It is a record with a
     * private canonical constructor so no other code path can produce an instance that has not been
     * range-checked, which is what stops malformed model output reaching the browser.
     */
    public record CoachInsightPayload(
            String summary,
            List<String> observations,
            List<String> recommendations,
            List<String> nextActions,
            List<String> warnings) {

        public CoachInsightPayload {
            observations = List.copyOf(observations);
            recommendations = List.copyOf(recommendations);
            nextActions = List.copyOf(nextActions);
            warnings = List.copyOf(warnings);
        }

        /** The validated provider payload. Package-private factory used by the parser. */
        static CoachInsightPayload of(String summary, List<String> observations,
                List<String> recommendations, List<String> nextActions, List<String> warnings) {
            return new CoachInsightPayload(summary, observations, recommendations, nextActions, warnings);
        }
    }

    /**
     * The public Coach response.
     *
     * <p>Carries {@code model} and {@code request_id} for support and traceability. The request id
     * is the same correlation id that appears in server logs, so a user-reported failure can be
     * matched to log lines without exposing anything about the user.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CoachInsightResponse(
            String summary,
            List<String> observations,
            List<String> recommendations,
            List<String> nextActions,
            List<String> warnings,
            String model,
            String requestId) {

        public static CoachInsightResponse from(CoachInsightPayload payload, String model, String requestId) {
            return new CoachInsightResponse(
                    payload.summary(),
                    payload.observations(),
                    payload.recommendations(),
                    payload.nextActions(),
                    payload.warnings(),
                    model,
                    requestId);
        }
    }
}
