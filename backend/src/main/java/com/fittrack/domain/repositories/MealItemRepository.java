package com.fittrack.domain.repositories;
import com.fittrack.domain.MealItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface MealItemRepository extends JpaRepository<MealItem, UUID>{}
