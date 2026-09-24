package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "personal_records")
public class PersonalRecord {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "exercise")
    private String exercise;
    public String getExercise() { return exercise; }
    public void setExercise(String value) { exercise = value; }
    @Column(name = "record_value")
    private BigDecimal recordValue;
    public BigDecimal getRecordValue() { return recordValue; }
    public void setRecordValue(BigDecimal value) { recordValue = value; }
    @Column(name = "unit")
    private String unit;
    public String getUnit() { return unit; }
    public void setUnit(String value) { unit = value; }
    @Column(name = "achieved_date")
    private LocalDate achievedDate;
    public LocalDate getAchievedDate() { return achievedDate; }
    public void setAchievedDate(LocalDate value) { achievedDate = value; }
}
