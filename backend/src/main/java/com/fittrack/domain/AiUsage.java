package com.fittrack.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "ai_usage")
public class AiUsage {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "feature")
    private String feature;
    public String getFeature() { return feature; }
    public void setFeature(String value) { feature = value; }
    @Column(name = "model")
    private String model;
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    @Column(name = "provider")
    private String provider;
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    @Column(name = "request_id")
    private UUID requestId;
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID value) { requestId = value; }
    @Column(name = "total_tokens")
    private Integer totalTokens;
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer value) { totalTokens = value; }
    @Column(name = "input_tokens")
    private Integer inputTokens;
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer value) { inputTokens = value; }
    @Column(name = "output_tokens")
    private Integer outputTokens;
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer value) { outputTokens = value; }
    @Column(name = "success")
    private Boolean success;
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean value) { success = value; }
    @Column(name = "latency_ms")
    private Long latencyMs;
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long value) { latencyMs = value; }
    @Column(name = "error_category")
    private String errorCategory;
    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String value) { errorCategory = value; }
    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal value) { estimatedCost = value; }
    @Column(name = "created_at")
    private Instant createdAt;
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
