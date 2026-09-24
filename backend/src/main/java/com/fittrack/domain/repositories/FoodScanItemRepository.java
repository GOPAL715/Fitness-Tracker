package com.fittrack.domain.repositories;
import com.fittrack.domain.FoodScanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface FoodScanItemRepository extends JpaRepository<FoodScanItem, UUID>{}
