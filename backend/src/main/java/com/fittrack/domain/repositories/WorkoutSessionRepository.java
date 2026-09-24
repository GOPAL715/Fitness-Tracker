package com.fittrack.domain.repositories;
import com.fittrack.domain.WorkoutSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface WorkoutSessionRepository extends JpaRepository<WorkoutSession, UUID> {
    java.util.Optional<WorkoutSession> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<WorkoutSession> findAllByUserIdOrderByIdDesc(UUID userId);
}
