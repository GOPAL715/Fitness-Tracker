package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "food_scan_items")
public class FoodScanItem {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "scan_id")
    private UUID scanId;
    public UUID getScanId() { return scanId; }
    public void setScanId(UUID value) { scanId = value; }
    @Column(name = "food_id")
    private UUID foodId;
    public UUID getFoodId() { return foodId; }
    public void setFoodId(UUID value) { foodId = value; }
    @Column(name = "food_name")
    private String foodName;
    public String getFoodName() { return foodName; }
    public void setFoodName(String value) { foodName = value; }
    @Column(name = "estimated_grams")
    private BigDecimal estimatedGrams;
    public BigDecimal getEstimatedGrams() { return estimatedGrams; }
    public void setEstimatedGrams(BigDecimal value) { estimatedGrams = value; }
    @Column(name = "confirmed_grams")
    private BigDecimal confirmedGrams;
    public BigDecimal getConfirmedGrams() { return confirmedGrams; }
    public void setConfirmedGrams(BigDecimal value) { confirmedGrams = value; }
    @Column(name = "confidence")
    private BigDecimal confidence;
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal value) { confidence = value; }
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
    @Column(name = "user_edited")
    private Boolean userEdited;
    public Boolean getUserEdited() { return userEdited; }
    public void setUserEdited(Boolean value) { userEdited = value; }
}
