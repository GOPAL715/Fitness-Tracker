package com.fittrack.domain.repositories;
import com.fittrack.domain.WorkoutTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface WorkoutTemplateRepository extends JpaRepository<WorkoutTemplate, UUID> {
    java.util.Optional<WorkoutTemplate> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<WorkoutTemplate> findAllByUserIdOrderByIdDesc(UUID userId);
}
