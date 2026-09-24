package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "foods")
public class Food {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "name")
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    @Column(name = "category")
    private String category;
    public String getCategory() { return category; }
    public void setCategory(String value) { category = value; }
    @Column(name = "serving_size")
    private BigDecimal servingSize;
    public BigDecimal getServingSize() { return servingSize; }
    public void setServingSize(BigDecimal value) { servingSize = value; }
    @Column(name = "serving_unit")
    private String servingUnit;
    public String getServingUnit() { return servingUnit; }
    public void setServingUnit(String value) { servingUnit = value; }
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
    @Column(name = "fiber_g")
    private BigDecimal fiberG;
    public BigDecimal getFiberG() { return fiberG; }
    public void setFiberG(BigDecimal value) { fiberG = value; }
}
