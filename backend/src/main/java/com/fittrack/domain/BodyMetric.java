package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "body_metrics")
public class BodyMetric {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "metric_date")
    private LocalDate metricDate;
    public LocalDate getMetricDate() { return metricDate; }
    public void setMetricDate(LocalDate value) { metricDate = value; }
    @Column(name = "weight_lb")
    private BigDecimal weightLb;
    public BigDecimal getWeightLb() { return weightLb; }
    public void setWeightLb(BigDecimal value) { weightLb = value; }
    @Column(name = "body_fat_pct")
    private BigDecimal bodyFatPct;
    public BigDecimal getBodyFatPct() { return bodyFatPct; }
    public void setBodyFatPct(BigDecimal value) { bodyFatPct = value; }
    @Column(name = "waist_in")
    private BigDecimal waistIn;
    public BigDecimal getWaistIn() { return waistIn; }
    public void setWaistIn(BigDecimal value) { waistIn = value; }
}
