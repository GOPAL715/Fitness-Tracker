package com.fittrack.domain.repositories;
import com.fittrack.domain.Habit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface HabitRepository extends JpaRepository<Habit, UUID> {
    java.util.Optional<Habit> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<Habit> findAllByUserIdOrderByIdDesc(UUID userId);
}
