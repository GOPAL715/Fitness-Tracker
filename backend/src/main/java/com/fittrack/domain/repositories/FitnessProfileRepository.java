package com.fittrack.domain.repositories;
import com.fittrack.domain.FitnessProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface FitnessProfileRepository extends JpaRepository<FitnessProfile, UUID> {
    java.util.Optional<FitnessProfile> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<FitnessProfile> findAllByUserIdOrderByIdDesc(UUID userId);
}
