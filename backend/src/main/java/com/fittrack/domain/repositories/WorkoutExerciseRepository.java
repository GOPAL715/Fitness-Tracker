package com.fittrack.domain.repositories;
import com.fittrack.domain.WorkoutExercise;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface WorkoutExerciseRepository extends JpaRepository<WorkoutExercise, UUID>{}
