package com.fittrack.domain.repositories;
import com.fittrack.domain.DailyMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface DailyMetricRepository extends JpaRepository<DailyMetric, UUID> {
    java.util.Optional<DailyMetric> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<DailyMetric> findAllByUserIdOrderByIdDesc(UUID userId);
}
