package com.fittrack.domain.repositories;
import com.fittrack.domain.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    java.util.Optional<Reminder> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<Reminder> findAllByUserIdOrderByIdDesc(UUID userId);
}
