package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "workout_template_exercises")
public class WorkoutTemplateItem {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "template_id")
    private UUID templateId;
    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID value) { templateId = value; }
    @Column(name = "exercise_id")
    private UUID exerciseId;
    public UUID getExerciseId() { return exerciseId; }
    public void setExerciseId(UUID value) { exerciseId = value; }
    @Column(name = "order_index")
    private Integer orderIndex;
    public Integer getOrderIndex() { return orderIndex; }
    public void setOrderIndex(Integer value) { orderIndex = value; }
    @Column(name = "target_sets")
    private Integer targetSets;
    public Integer getTargetSets() { return targetSets; }
    public void setTargetSets(Integer value) { targetSets = value; }
    @Column(name = "target_reps")
    private String targetReps;
    public String getTargetReps() { return targetReps; }
    public void setTargetReps(String value) { targetReps = value; }
    @Column(name = "target_weight")
    private BigDecimal targetWeight;
    public BigDecimal getTargetWeight() { return targetWeight; }
    public void setTargetWeight(BigDecimal value) { targetWeight = value; }
}
