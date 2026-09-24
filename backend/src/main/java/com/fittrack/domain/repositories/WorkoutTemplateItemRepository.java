package com.fittrack.domain.repositories;
import com.fittrack.domain.WorkoutTemplateItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface WorkoutTemplateItemRepository extends JpaRepository<WorkoutTemplateItem, UUID>{}
