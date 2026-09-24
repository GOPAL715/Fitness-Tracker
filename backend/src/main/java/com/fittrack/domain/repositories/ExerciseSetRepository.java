package com.fittrack.domain.repositories;
import com.fittrack.domain.ExerciseSet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ExerciseSetRepository extends JpaRepository<ExerciseSet, UUID>{}
