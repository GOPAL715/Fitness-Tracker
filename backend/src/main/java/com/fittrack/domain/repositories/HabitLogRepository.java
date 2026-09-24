package com.fittrack.domain.repositories;
import com.fittrack.domain.HabitLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface HabitLogRepository extends JpaRepository<HabitLog, UUID> {
    java.util.Optional<HabitLog> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<HabitLog> findAllByUserIdOrderByIdDesc(UUID userId);
}
