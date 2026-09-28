package com.fittrack.acceptance.support;

import com.fittrack.ai.AiProvider;
import com.fittrack.ai.AiProviderException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic, offline stand-in for the {@link AiProvider} boundary.
 *
 * <p>Each test selects a {@link Mode}; there are no network calls and no credentials. Invocation
 * counters let tests prove <em>whether</em> the provider was reached, not merely what HTTP status
 * came back.
 */
public class FakeAiProvider implements AiProvider {

    public static final String PROVIDER = "fake-provider";
    public static final String MODEL = "fake-model-v1";
    public static final int INPUT_TOKENS = 1_200;
    public static final int OUTPUT_TOKENS = 340;
    public static final int TOTAL_TOKENS = INPUT_TOKENS + OUTPUT_TOKENS;

    /** Marker that must never appear in any client-facing response body. */
    public static final String SECRET = "sk-live-FAKE-SECRET-MUST-NOT-LEAK";

    public enum Mode {
        /** A. Plain success with no token metadata. */
        SUCCESS,
        /** B. Success carrying provider/model/input/output token metadata. */
        SUCCESS_WITH_TOKENS,
        /** C. Upstream read timeout. */
        TIMEOUT,
        /** D. Upstream returned 5xx. */
        PROVIDER_SERVER_ERROR,
        /** E. Malformed / incomplete upstream payload. */
        MALFORMED_RESPONSE,
        /** F. Failure raised by the application rather than the provider. */
        APPLICATION_EXCEPTION,
        /** G. Success with token metadata; cost is non-zero only when pricing is configured. */
        SUCCESS_WITH_TOKENS_AND_PRICING,
        /** Provider succeeds, but the detection is unusable by the application. */
        INVALID_FOOD_ITEM
    }

    private final AtomicInteger foodCalls = new AtomicInteger();
    private final AtomicInteger coachCalls = new AtomicInteger();
    private volatile Mode mode = Mode.SUCCESS;
    private volatile String lastCoachContext;

    public void reset() {
        mode = Mode.SUCCESS;
        foodCalls.set(0);
        coachCalls.set(0);
        lastCoachContext = null;
    }

    public void use(Mode selected) { this.mode = selected; }

    public Mode mode() { return mode; }

    public int foodCalls() { return foodCalls.get(); }

    public int coachCalls() { return coachCalls.get(); }

    /** Total provider invocations across every AI feature. */
    public int totalCalls() { return foodCalls.get() + coachCalls.get(); }

    public String lastCoachContext() { return lastCoachContext; }

    @Override
    public FoodAnalysis analyzeFood(byte[] imageBytes, String contentType) {
        foodCalls.incrementAndGet();
        return switch (mode) {
            case SUCCESS -> new FoodAnalysis(MODEL, List.of(new FoodItem("Test Rice", 120d, 0.9d)), null);
            case SUCCESS_WITH_TOKENS, SUCCESS_WITH_TOKENS_AND_PRICING -> new FoodAnalysis(MODEL,
                    List.of(new FoodItem("Test Rice", 120d, 0.9d)),
                    new ProviderUsage(PROVIDER, MODEL, INPUT_TOKENS, OUTPUT_TOKENS));
            case INVALID_FOOD_ITEM -> new FoodAnalysis(MODEL,
                    List.of(new FoodItem("Test Rice", 0d, 0.9d)),
                    new ProviderUsage(PROVIDER, MODEL, INPUT_TOKENS, OUTPUT_TOKENS));
            case TIMEOUT -> throw new AiProviderException(
                    "upstream timed out after 45000ms for " + SECRET, "timeout");
            case PROVIDER_SERVER_ERROR -> throw new AiProviderException(
                    "upstream returned 503 for " + SECRET, "provider");
            case MALFORMED_RESPONSE -> throw new AiProviderException(
                    "upstream payload was truncated: {\"items\":[ for " + SECRET, "malformed");
            case APPLICATION_EXCEPTION -> throw new IllegalStateException(
                    "catalog lookup failed while recording usage for " + SECRET);
        };
    }

    @Override
    public String analyzeCoach(String factualContext) { return analyzeCoachWithMetadata(factualContext).text(); }

    /** A structurally valid Phase 9 Coach answer. The parser rejects anything else. */
    public static final String VALID_COACH_JSON = """
            {"summary":"You trained consistently this week and hit your protein target.",
             "observations":["Three completed sessions.","Protein averaged above target."],
             "recommendations":["Keep the current weekly rhythm."],
             "next_actions":["Schedule next week's first session."],
             "warnings":[]}""";

    @Override
    public CoachAnalysis analyzeCoachWithMetadata(String factualContext) {
        return analyzeCoachMessages(List.of(new CoachMessage("system", factualContext)));
    }

    @Override
    public CoachAnalysis analyzeCoachMessages(List<CoachMessage> messages) {
        coachCalls.incrementAndGet();
        lastCoachContext = messages.stream().map(CoachMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);
        return switch (mode) {
            case SUCCESS -> new CoachAnalysis(MODEL, VALID_COACH_JSON, null);
            case SUCCESS_WITH_TOKENS, SUCCESS_WITH_TOKENS_AND_PRICING -> new CoachAnalysis(MODEL,
                    VALID_COACH_JSON,
                    new ProviderUsage(PROVIDER, MODEL, INPUT_TOKENS, OUTPUT_TOKENS));
            case TIMEOUT -> throw new AiProviderException(
                    "upstream timed out after 20000ms for " + SECRET, "timeout");
            case PROVIDER_SERVER_ERROR -> throw new AiProviderException(
                    "upstream returned 503 for " + SECRET, "provider");
            case MALFORMED_RESPONSE -> throw new AiProviderException(
                    "upstream payload was truncated: {\"content\": for " + SECRET, "malformed");
            case APPLICATION_EXCEPTION, INVALID_FOOD_ITEM ->
                    throw new IllegalStateException("coach assembly failed for " + SECRET);
        };
    }
}
