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
    public record DashboardResponse(LocalDate date, String timezone, boolean timezoneResolved,
                                    ActivitySummary activity, NutritionSummary nutrition,
                                    WorkoutSummary workout, BodySummary body,
                                    TargetSummary targets) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivitySummary(long steps, long activeMinutes, BigDecimal sleepHours, long caloriesBurned, long waterOz, long readiness) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NutritionSummary(BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG, BigDecimal fiberG) {}
    /**
     * Training activity for a day, split by which record type produced it.
     *
     * <p>{@code quickLogged} and {@code loggedSessions} are the two distinct concepts;
     * {@code sessions} is their sum and is the number the rest of the product should treat as
     * "workouts performed". {@code calories} is nullable because {@code workout_sessions} has no
     * calorie column, so a window containing a logged session has no known calorie total.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutSummary(long sessions, long minutes, Long calories, long quickLogged, long loggedSessions) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BodySummary(BigDecimal weightLb, BigDecimal bodyFatPct) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetSummary(Integer stepTarget, Integer calorieTarget, Integer proteinTargetG, Integer waterTargetOz, BigDecimal targetWeightLb) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TargetWeight(BigDecimal targetWeightLb) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutPoint(LocalDate date, long sessions, long minutes, Long calories,
                               long quickLogged, long loggedSessions) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NutritionPoint(LocalDate date, BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG, BigDecimal fatG, BigDecimal fiberG) {}
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
    /**
     * The response for {@code GET /api/v1/analytics/trends}.
     *
     * <p>{@code timezone} is the zone every bucket in this response was computed in, and
     * {@code timezoneResolved} says whether it is the caller's own stored zone ({@code true}) or the
     * UTC fallback applied because they have not set one ({@code false}). A client can therefore
     * label the series honestly instead of inferring a zone from an offset.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TrendsResponse(LocalDate from, LocalDate to, String bucket, String timezone,
                                 boolean timezoneResolved, List<TrendBucket> buckets) {}
    /**
     * One day, week or month.
     *
     * <p>{@code start} is the bucket's own first day, which for a partial bucket can precede
     * {@code from}. {@code days} is how many days of that bucket the requested range actually
     * covered, so a 3-day slice of a 7-day week is visibly not a whole week.
     *
     * <p>Every measure inside is nullable and null means "not recorded", never "zero".
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TrendBucket(LocalDate start, LocalDate end, long days, Long steps, Long activeMinutes,
                              Long activityCaloriesBurned, BigDecimal sleepHoursAvg, BigDecimal sleepHoursTotal,
                              BigDecimal nutritionCalories, BigDecimal proteinG, BigDecimal carbsG,
                              BigDecimal fatG, BigDecimal fiberG, Long workouts, Long workoutMinutes,
                              Long workoutCalories, Long quickLogged, Long loggedSessions) {}

    public record CalendarDay(LocalDate date, CalendarSummary summary) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CalendarSummary(long steps, long waterOz, long caloriesBurned, long activeMinutes, BigDecimal sleepHours, long workouts, long meals, long habitsCompleted) {}
}
