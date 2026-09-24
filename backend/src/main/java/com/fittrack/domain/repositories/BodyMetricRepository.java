package com.fittrack.domain.repositories;
import com.fittrack.domain.BodyMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface BodyMetricRepository extends JpaRepository<BodyMetric, UUID> {
    java.util.Optional<BodyMetric> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<BodyMetric> findAllByUserIdOrderByIdDesc(UUID userId);
}
