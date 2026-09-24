package com.fittrack.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "fitness_profile")
public class FitnessProfile {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "display_name")
    private String displayName;
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { displayName = value; }
    @Column(name = "goal")
    private String goal;
    public String getGoal() { return goal; }
    public void setGoal(String value) { goal = value; }
    @Column(name = "fitness_level")
    private String fitnessLevel;
    public String getFitnessLevel() { return fitnessLevel; }
    public void setFitnessLevel(String value) { fitnessLevel = value; }
    @Column(name = "activity_target")
    private Integer activityTarget;
    public Integer getActivityTarget() { return activityTarget; }
    public void setActivityTarget(Integer value) { activityTarget = value; }
    @Column(name = "weekly_minutes")
    private Integer weeklyMinutes;
    public Integer getWeeklyMinutes() { return weeklyMinutes; }
    public void setWeeklyMinutes(Integer value) { weeklyMinutes = value; }
    @Column(name = "calorie_target")
    private Integer calorieTarget;
    public Integer getCalorieTarget() { return calorieTarget; }
    public void setCalorieTarget(Integer value) { calorieTarget = value; }
    @Column(name = "water_target_oz")
    private Integer waterTargetOz;
    public Integer getWaterTargetOz() { return waterTargetOz; }
    public void setWaterTargetOz(Integer value) { waterTargetOz = value; }
}
