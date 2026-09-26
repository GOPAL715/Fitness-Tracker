package com.fittrack.analytics;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

public final class AnalyticsDtos {
    private AnalyticsDtos() {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DashboardResponse(LocalDate date, ActivitySummary activity, NutritionSummary nutrition, WorkoutSummary workout, BodySummary body, TargetSummary targets) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivitySummary(long steps, long activeMinutes, BigDecimal sleepHours, long caloriesBurned, long waterOz, long readiness) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NutritionSummary(BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutSummary(long sessions, long minutes, long calories) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BodySummary(BigDecimal weightLb, BigDecimal bodyFatPct) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetSummary(Integer stepTarget, Integer calorieTarget, Integer proteinTargetG, Integer waterTargetOz, BigDecimal targetWeightLb) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetWeight(BigDecimal targetWeightLb) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutPoint(LocalDate date, long sessions, long minutes, long calories) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NutritionPoint(LocalDate date, BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivityPoint(LocalDate date, Long steps, Long activeMinutes, BigDecimal sleepHours, Long caloriesBurned) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BodyPoint(LocalDate date, BigDecimal weightLb, BigDecimal bodyFatPct, BigDecimal waistIn) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NutritionResponse(LocalDate from, LocalDate to, List<NutritionPoint> daily, NutritionSummary totals) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProgressResponse(LocalDate from, LocalDate to, List<BodyPoint> body, List<ActivityPoint> activity, TargetWeight target) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WeeklyResponse(LocalDate from, LocalDate to, List<ActivityPoint> activity, List<NutritionPoint> nutrition, List<WorkoutPoint> workouts) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AiUsageView(UUID id, UUID userId, String feature, String model, String provider, UUID requestId, Integer inputTokens, Integer outputTokens, Integer totalTokens, boolean success, BigDecimal estimatedCost, Long latencyMs, String errorCategory) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HabitPoint(String name, long completed) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CalendarDay(LocalDate date, CalendarSummary summary) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CalendarSummary(long steps, long waterOz, long caloriesBurned, long activeMinutes, BigDecimal sleepHours, long workouts, long meals, long habitsCompleted) {}
}
