package com.fittrack.domain;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "coach_notifications")
public class CoachNotification {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "title")
    private String title;
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    @Column(name = "message")
    private String message;
    public String getMessage() { return message; }
    public void setMessage(String value) { message = value; }
    @Column(name = "kind")
    private String kind;
    public String getKind() { return kind; }
    public void setKind(String value) { kind = value; }
    @Column(name = "is_read")
    private Boolean read;
    public Boolean getRead() { return read; }
    public void setRead(Boolean value) { read = value; }
    @Column(name = "created_at")
    private Instant createdAt;
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
