package com.fittrack.domain;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "workouts")
public class Workout {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "title")
    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    @Column(name = "workout_type")
    private String workoutType;
    public String getWorkoutType() { return workoutType; }
    public void setWorkoutType(String value) { workoutType = value; }
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer value) { durationMinutes = value; }
    @Column(name = "calories_burned")
    private Integer caloriesBurned;
    public Integer getCaloriesBurned() { return caloriesBurned; }
    public void setCaloriesBurned(Integer value) { caloriesBurned = value; }
    @Column(name = "workout_date")
    private LocalDate workoutDate;
    public LocalDate getWorkoutDate() { return workoutDate; }
    public void setWorkoutDate(LocalDate value) { workoutDate = value; }
    @Column(name = "completed")
    private Boolean completed;
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean value) { completed = value; }
}
