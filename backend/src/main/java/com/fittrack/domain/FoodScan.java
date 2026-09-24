package com.fittrack.domain;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "food_scans")
public class FoodScan {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "meal_id")
    private UUID mealId;
    public UUID getMealId() { return mealId; }
    public void setMealId(UUID value) { mealId = value; }
    @Column(name = "status")
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    @Column(name = "model")
    private String model;
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    @Column(name = "image_path")
    private String imagePath;
    public String getImagePath() { return imagePath; }
    public void setImagePath(String value) { imagePath = value; }
    @Column(name = "error")
    private String error;
    public String getError() { return error; }
    public void setError(String value) { error = value; }
    @Column(name = "created_at")
    private Instant createdAt;
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
