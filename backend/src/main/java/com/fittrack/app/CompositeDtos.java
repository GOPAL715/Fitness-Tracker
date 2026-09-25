package com.fittrack.app;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CompositeDtos {
    private CompositeDtos() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutSessionData(
            @NotBlank @Size(max=160) String title,
            @NotBlank @Size(max=60) String workoutType,
            @Min(0) @Max(1440) Integer durationMinutes,
            @Min(1) @Max(10) Integer perceivedEffort,
            @Size(max=2000) String notes,
            boolean completed) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExerciseSetData(
            @NotNull @Min(1) @Max(100) Integer setNumber,
            @Min(0) @Max(1000) Integer reps,
            @DecimalMin("0") @DecimalMax("2000") BigDecimal weight,
            @DecimalMin("0") @DecimalMax("10") BigDecimal rpe,
            boolean completed) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SessionExerciseData(
            @NotNull UUID exerciseId,
            @NotNull @Min(0) @Max(1000) Integer orderIndex,
            @Size(max=2000) String notes,
            @NotNull @Valid List<ExerciseSetData> sets) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutSessionCompleteRequest(
            @NotNull @Valid WorkoutSessionData session,
            @NotNull @Size(min=1, max=100) List<@Valid SessionExerciseData> exercises) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TemplateData(
            @NotBlank @Size(max=160) String name,
            @Size(max=2000) String description,
            @NotBlank @Size(max=60) String workoutType,
            @Min(0) @Max(1440) Integer estimatedMinutes,
            boolean favorite) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TemplateExerciseData(
            @NotNull UUID exerciseId,
            @NotNull @Min(0) @Max(1000) Integer orderIndex,
            @NotNull @Min(1) @Max(100) Integer targetSets,
            @NotBlank @Size(max=40) String targetReps,
            @DecimalMin("0") @DecimalMax("2000") BigDecimal targetWeight) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkoutTemplateCompleteRequest(
            @NotNull @Valid TemplateData template,
            @NotNull @Size(min=1, max=100) List<@Valid TemplateExerciseData> exercises) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MealData(
            @NotNull LocalDate mealDate,
            @NotBlank @Pattern(regexp="BREAKFAST|LUNCH|DINNER|SNACK") String mealType,
            @NotBlank @Size(max=200) String name,
            @Size(max=40) String source) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MealItemData(
            @NotNull UUID foodId,
            @NotNull @DecimalMin("1") @DecimalMax("5000") BigDecimal grams,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000") BigDecimal quantity) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MealCompleteRequest(
            @NotNull @Valid MealData meal,
            @NotNull @Size(min=1, max=100) List<@Valid MealItemData> items) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CompositeResponse(UUID id, String type, int childCount) {}
}
