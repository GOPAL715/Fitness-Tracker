package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "goals")
public class Goal {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "goal_type")
    private String goalType;
    public String getGoalType() { return goalType; }
    public void setGoalType(String value) { goalType = value; }
    @Column(name = "title")
    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    @Column(name = "start_value")
    private BigDecimal startValue;
    public BigDecimal getStartValue() { return startValue; }
    public void setStartValue(BigDecimal value) { startValue = value; }
    @Column(name = "target_value")
    private BigDecimal targetValue;
    public BigDecimal getTargetValue() { return targetValue; }
    public void setTargetValue(BigDecimal value) { targetValue = value; }
    @Column(name = "current_value")
    private BigDecimal currentValue;
    public BigDecimal getCurrentValue() { return currentValue; }
    public void setCurrentValue(BigDecimal value) { currentValue = value; }
    @Column(name = "unit")
    private String unit;
    public String getUnit() { return unit; }
    public void setUnit(String value) { unit = value; }
    @Column(name = "start_date")
    private LocalDate startDate;
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate value) { startDate = value; }
    @Column(name = "target_date")
    private LocalDate targetDate;
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate value) { targetDate = value; }
    @Column(name = "status")
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
}
