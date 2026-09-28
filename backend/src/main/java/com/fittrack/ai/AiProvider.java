package com.fittrack.ai;

import java.util.List;

/** AI boundary for image recognition. Nutrition is deliberately not supplied by the model. */
public interface AiProvider {
    FoodAnalysis analyzeFood(byte[] imageBytes, String contentType);
    String analyzeCoach(String factualContext);
    default CoachAnalysis analyzeCoachWithMetadata(String factualContext) { return new CoachAnalysis(null, analyzeCoach(factualContext), null); }
    /**
     * Runs the Coach over an explicit message list.
     *
     * <p>Taking messages rather than one flat string is what keeps the system instruction, the
     * untrusted user question and the server-generated context in separate provider messages.
     */
    default CoachAnalysis analyzeCoachMessages(List<CoachMessage> messages) {
        return new CoachAnalysis(null, analyzeCoach(messages.isEmpty() ? "" : messages.get(0).content()), null);
    }

    record FoodItem(String name, double grams, double confidence) {}
    /** One provider message for the Coach, so instructions and untrusted data stay separate. */
    record CoachMessage(String role, String content) {}
    record ProviderUsage(String provider, String model, Integer inputTokens, Integer outputTokens) {
        Integer totalTokens() { return inputTokens == null && outputTokens == null ? null : (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens); }
    }
    record FoodAnalysis(String model, List<FoodItem> items, ProviderUsage usage) {
        public FoodAnalysis(String model, List<FoodItem> items) { this(model, items, null); }
    }
    record CoachAnalysis(String model, String text, ProviderUsage usage) {}
}