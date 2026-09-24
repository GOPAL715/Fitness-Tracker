package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "exercise_sets")
public class ExerciseSet {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "workout_exercise_id")
    private UUID workoutExerciseId;
    public UUID getWorkoutExerciseId() { return workoutExerciseId; }
    public void setWorkoutExerciseId(UUID value) { workoutExerciseId = value; }
    @Column(name = "set_number")
    private Integer setNumber;
    public Integer getSetNumber() { return setNumber; }
    public void setSetNumber(Integer value) { setNumber = value; }
    @Column(name = "reps")
    private Integer reps;
    public Integer getReps() { return reps; }
    public void setReps(Integer value) { reps = value; }
    @Column(name = "weight")
    private BigDecimal weight;
    public BigDecimal getWeight() { return weight; }
    public void setWeight(BigDecimal value) { weight = value; }
    @Column(name = "completed")
    private Boolean completed;
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean value) { completed = value; }
}
