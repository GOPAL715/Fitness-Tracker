package com.fittrack.domain.repositories;
import com.fittrack.domain.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface GoalRepository extends JpaRepository<Goal, UUID> {
    java.util.Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<Goal> findAllByUserIdOrderByIdDesc(UUID userId);
}
