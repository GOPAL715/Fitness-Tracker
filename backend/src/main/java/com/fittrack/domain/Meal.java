package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "meals")
public class Meal {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "meal_date")
    private LocalDate mealDate;
    public LocalDate getMealDate() { return mealDate; }
    public void setMealDate(LocalDate value) { mealDate = value; }
    @Column(name = "meal_type")
    private String mealType;
    public String getMealType() { return mealType; }
    public void setMealType(String value) { mealType = value; }
    @Column(name = "name")
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    @Column(name = "source")
    private String source;
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    @Column(name = "calories")
    private BigDecimal calories;
    public BigDecimal getCalories() { return calories; }
    public void setCalories(BigDecimal value) { calories = value; }
    @Column(name = "protein_g")
    private BigDecimal proteinG;
    public BigDecimal getProteinG() { return proteinG; }
    public void setProteinG(BigDecimal value) { proteinG = value; }
    @Column(name = "carbs_g")
    private BigDecimal carbsG;
    public BigDecimal getCarbsG() { return carbsG; }
    public void setCarbsG(BigDecimal value) { carbsG = value; }
    @Column(name = "fat_g")
    private BigDecimal fatG;
    public BigDecimal getFatG() { return fatG; }
    public void setFatG(BigDecimal value) { fatG = value; }
}
