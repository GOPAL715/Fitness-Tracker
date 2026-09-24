package com.fittrack.domain.repositories;
import com.fittrack.domain.Exercise;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ExerciseRepository extends JpaRepository<Exercise, UUID>{}
