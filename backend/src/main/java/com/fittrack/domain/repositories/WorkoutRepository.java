package com.fittrack.domain.repositories;
import com.fittrack.domain.Workout;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface WorkoutRepository extends JpaRepository<Workout, UUID> {
    java.util.Optional<Workout> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<Workout> findAllByUserIdOrderByIdDesc(UUID userId);
}
