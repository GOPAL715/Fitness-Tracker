package com.fittrack.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "workout_exercises")
public class WorkoutExercise {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "workout_session_id")
    private UUID workoutSessionId;
    public UUID getWorkoutSessionId() { return workoutSessionId; }
    public void setWorkoutSessionId(UUID value) { workoutSessionId = value; }
    @Column(name = "exercise_id")
    private UUID exerciseId;
    public UUID getExerciseId() { return exerciseId; }
    public void setExerciseId(UUID value) { exerciseId = value; }
    @Column(name = "order_index")
    private Integer orderIndex;
    public Integer getOrderIndex() { return orderIndex; }
    public void setOrderIndex(Integer value) { orderIndex = value; }
    @Column(name = "notes")
    private String notes;
    public String getNotes() { return notes; }
    public void setNotes(String value) { notes = value; }
}
