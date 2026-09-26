package com.fittrack.ai;

import java.util.List;

/** AI boundary for image recognition. Nutrition is deliberately not supplied by the model. */
public interface AiProvider {
    FoodAnalysis analyzeFood(byte[] imageBytes, String contentType);
    String analyzeCoach(String factualContext);
    default CoachAnalysis analyzeCoachWithMetadata(String factualContext) { return new CoachAnalysis(null, analyzeCoach(factualContext), null); }

    record FoodItem(String name, double grams, double confidence) {}
    record ProviderUsage(String provider, String model, Integer inputTokens, Integer outputTokens) {
        Integer totalTokens() { return inputTokens == null && outputTokens == null ? null : (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens); }
    }
    record FoodAnalysis(String model, List<FoodItem> items, ProviderUsage usage) {
        public FoodAnalysis(String model, List<FoodItem> items) { this(model, items, null); }
    }
    record CoachAnalysis(String model, String text, ProviderUsage usage) {}
}