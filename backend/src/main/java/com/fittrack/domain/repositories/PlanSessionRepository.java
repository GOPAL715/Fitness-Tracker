package com.fittrack.domain.repositories;
import com.fittrack.domain.PlanSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PlanSessionRepository extends JpaRepository<PlanSession, UUID> {
    java.util.Optional<PlanSession> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<PlanSession> findAllByUserIdOrderByIdDesc(UUID userId);
}
