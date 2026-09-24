package com.fittrack.domain;

import jakarta.persistence.*;
import java.util.UUID;
import java.util.UUID;

@Entity
@Table(name = "habits")
public class Habit {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    public UUID getUserId() { return userId; }
    public void setUserId(UUID value) { userId = value; }
    @Column(name = "name")
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    @Column(name = "description")
    private String description;
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    @Column(name = "icon")
    private String icon;
    public String getIcon() { return icon; }
    public void setIcon(String value) { icon = value; }
    @Column(name = "target_per_week")
    private Integer targetPerWeek;
    public Integer getTargetPerWeek() { return targetPerWeek; }
    public void setTargetPerWeek(Integer value) { targetPerWeek = value; }
    @Column(name = "color")
    private String color;
    public String getColor() { return color; }
    public void setColor(String value) { color = value; }
    @Column(name = "active")
    private Boolean active;
    public Boolean getActive() { return active; }
    public void setActive(Boolean value) { active = value; }
}
