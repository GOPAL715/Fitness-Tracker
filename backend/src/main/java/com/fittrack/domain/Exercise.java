package com.fittrack.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "exercises")
public class Exercise {
    @Id
    private UUID id;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    @PrePersist void init() { if (id == null) id = UUID.randomUUID(); }
    @Column(name = "name")
    private String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    @Column(name = "muscle_group")
    private String muscleGroup;
    public String getMuscleGroup() { return muscleGroup; }
    public void setMuscleGroup(String value) { muscleGroup = value; }
    @Column(name = "equipment")
    private String equipment;
    public String getEquipment() { return equipment; }
    public void setEquipment(String value) { equipment = value; }
    @Column(name = "is_compound")
    private Boolean isCompound;
    public Boolean getIsCompound() { return isCompound; }
    public void setIsCompound(Boolean value) { isCompound = value; }
}
