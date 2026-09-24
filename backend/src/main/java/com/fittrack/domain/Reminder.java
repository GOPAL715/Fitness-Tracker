package com.fittrack.domain;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "reminders")
public class Reminder {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "type")
    private String type;
    public String getType() { return type; }
    public void setType(String value) { type = value; }
    @Column(name = "title")
    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    @Column(name = "message")
    private String message;
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    @Column(name = "scheduled_time")
    private LocalTime scheduledTime;
    public LocalTime getScheduledTime() { return scheduledTime; }
    public void setScheduledTime(LocalTime value) { scheduledTime = value; }
    @Column(name = "days_of_week")
    private String daysOfWeek;
    public String getDaysOfWeek() { return daysOfWeek; }
    public void setDaysOfWeek(String value) { daysOfWeek = value; }
    @Column(name = "enabled")
    private Boolean enabled;
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean value) { enabled = value; }
}
