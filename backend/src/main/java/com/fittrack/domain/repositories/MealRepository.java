package com.fittrack.domain.repositories;
import com.fittrack.domain.Meal;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface MealRepository extends JpaRepository<Meal, UUID> {
    java.util.Optional<Meal> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<Meal> findAllByUserIdOrderByIdDesc(UUID userId);
}
