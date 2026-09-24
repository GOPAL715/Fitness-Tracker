package com.fittrack.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "workout_templates")
public class WorkoutTemplate {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "name")
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    @Column(name = "description")
    private String description;
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    @Column(name = "workout_type")
    private String workoutType;
    public String getWorkoutType() { return workoutType; }
    public void setWorkoutType(String value) { workoutType = value; }
    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;
    public Integer getEstimatedMinutes() { return estimatedMinutes; }
    public void setEstimatedMinutes(Integer value) { estimatedMinutes = value; }
    @Column(name = "is_favorite")
    private Boolean favorite;
    public Boolean getFavorite() { return favorite; }
    public void setFavorite(Boolean value) { favorite = value; }
}
