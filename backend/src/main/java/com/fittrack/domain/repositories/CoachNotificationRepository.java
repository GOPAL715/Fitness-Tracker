package com.fittrack.domain.repositories;
import com.fittrack.domain.CoachNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface CoachNotificationRepository extends JpaRepository<CoachNotification, UUID> {
    java.util.Optional<CoachNotification> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<CoachNotification> findAllByUserIdOrderByIdDesc(UUID userId);
}
