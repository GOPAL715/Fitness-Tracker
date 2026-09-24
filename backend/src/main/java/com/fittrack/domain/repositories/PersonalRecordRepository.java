package com.fittrack.domain.repositories;
import com.fittrack.domain.PersonalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PersonalRecordRepository extends JpaRepository<PersonalRecord, UUID> {
    java.util.Optional<PersonalRecord> findByIdAndUserId(UUID id, UUID userId);
    java.util.List<PersonalRecord> findAllByUserIdOrderByIdDesc(UUID userId);
}
