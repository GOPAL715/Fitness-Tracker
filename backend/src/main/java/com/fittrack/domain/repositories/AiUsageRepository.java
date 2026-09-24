package com.fittrack.domain.repositories;
import com.fittrack.domain.AiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface AiUsageRepository extends JpaRepository<AiUsage, UUID> {
    java.util.Optional<AiUsage> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<AiUsage> findAllByUserIdOrderByIdDesc(UUID userId);
}
