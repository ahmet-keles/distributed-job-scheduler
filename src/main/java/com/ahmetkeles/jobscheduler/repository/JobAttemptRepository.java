package com.ahmetkeles.jobscheduler.repository;

import com.ahmetkeles.jobscheduler.domain.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {

    Optional<JobAttempt> findByJobIdAndAttemptNumber(UUID jobId, int attemptNumber);

    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);
}
