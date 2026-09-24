package com.fittrack.ai;

import java.util.List;

/** AI boundary for image recognition. Nutrition is deliberately not supplied by the model. */
public interface AiProvider {
    FoodAnalysis analyzeFood(byte[] imageBytes, String contentType);
    String analyzeCoach(String factualContext);

    record FoodItem(String name, double grams, double confidence) {}
    record FoodAnalysis(String model, List<FoodItem> items) {}
}