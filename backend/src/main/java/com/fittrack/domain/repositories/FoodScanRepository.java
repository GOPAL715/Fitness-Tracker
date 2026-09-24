package com.fittrack.domain.repositories;
import com.fittrack.domain.FoodScan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface FoodScanRepository extends JpaRepository<FoodScan, UUID> {
    java.util.Optional<FoodScan> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<FoodScan> findAllByUserIdOrderByIdDesc(UUID userId);
}
