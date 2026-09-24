package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "meal_items")
public class MealItem {
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
    @Column(name = "food_id")
    private UUID foodId;
    public UUID getFoodId() { return foodId; }
    public void setFoodId(UUID value) { foodId = value; }
    @Column(name = "food_name")
    private String foodName;
    public String getFoodName() { return foodName; }
    public void setFoodName(String value) { foodName = value; }
    @Column(name = "quantity")
    private BigDecimal quantity;
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { quantity = value; }
    @Column(name = "grams")
    private BigDecimal grams;
    public BigDecimal getGrams() { return grams; }
    public void setGrams(BigDecimal value) { grams = value; }
    @Column(name = "calories")
    private BigDecimal calories;
    public BigDecimal getCalories() { return calories; }
    public void setCalories(BigDecimal value) { calories = value; }
}
