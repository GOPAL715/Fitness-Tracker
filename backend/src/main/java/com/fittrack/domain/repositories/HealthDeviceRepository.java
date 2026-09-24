package com.fittrack.domain.repositories;
import com.fittrack.domain.HealthDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface HealthDeviceRepository extends JpaRepository<HealthDevice, UUID> {
    java.util.Optional<HealthDevice> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<HealthDevice> findAllByUserIdOrderByIdDesc(UUID userId);
}
