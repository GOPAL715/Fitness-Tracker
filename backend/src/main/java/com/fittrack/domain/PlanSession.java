package com.fittrack.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "plan_sessions")
public class PlanSession {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "day_index")
    private Integer dayIndex;
    public Integer getDayIndex() { return dayIndex; }
    public void setDayIndex(Integer value) { dayIndex = value; }
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
    @Column(name = "completed")
    private Boolean completed;
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean value) { completed = value; }
}
