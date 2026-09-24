package com.fittrack.domain.repositories;
import com.fittrack.domain.Food;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface FoodRepository extends JpaRepository<Food, UUID>{}
