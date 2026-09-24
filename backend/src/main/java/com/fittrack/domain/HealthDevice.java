package com.fittrack.domain;

import jakarta.persistence.*;
import java.time.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "health_devices")
public class HealthDevice {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "device_name")
    private String deviceName;
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String value) { deviceName = value; }
    @Column(name = "device_type")
    private String deviceType;
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String value) { deviceType = value; }
    @Column(name = "status")
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    @Column(name = "last_sync")
    private Instant lastSync;
    public Instant getLastSync() { return lastSync; }
    public void setLastSync(Instant value) { lastSync = value; }
}
