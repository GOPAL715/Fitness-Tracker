package com.fittrack.domain;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "habit_logs")
public class HabitLog {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "habit_id")
    private UUID habitId;
    public UUID getHabitId() { return habitId; }
    public void setHabitId(UUID value) { habitId = value; }
    @Column(name = "log_date")
    private LocalDate logDate;
    public LocalDate getLogDate() { return logDate; }
    public void setLogDate(LocalDate value) { logDate = value; }
    @Column(name = "completed")
    private Boolean completed;
    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean value) { completed = value; }
}
