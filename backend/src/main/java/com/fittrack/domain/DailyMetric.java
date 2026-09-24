package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "daily_metrics")
public class DailyMetric {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "metric_date")
    private LocalDate metricDate;
    public LocalDate getMetricDate() { return metricDate; }
    public void setMetricDate(LocalDate value) { metricDate = value; }
    @Column(name = "steps")
    private Integer steps;
    public Integer getSteps() { return steps; }
    public void setSteps(Integer value) { steps = value; }
    @Column(name = "sleep_hours")
    private BigDecimal sleepHours;
    public BigDecimal getSleepHours() { return sleepHours; }
    public void setSleepHours(BigDecimal value) { sleepHours = value; }
    @Column(name = "calories_burned")
    private Integer caloriesBurned;
    public Integer getCaloriesBurned() { return caloriesBurned; }
    public void setCaloriesBurned(Integer value) { caloriesBurned = value; }
    @Column(name = "water_oz")
    private Integer waterOz;
    public Integer getWaterOz() { return waterOz; }
    public void setWaterOz(Integer value) { waterOz = value; }
    @Column(name = "active_minutes")
    private Integer activeMinutes;
    public Integer getActiveMinutes() { return activeMinutes; }
    public void setActiveMinutes(Integer value) { activeMinutes = value; }
}
