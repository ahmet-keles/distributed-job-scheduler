package com.ahmetkeles.jobscheduler.repository;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /**
     * Locks the row for a state transition (cancel). Serializes against the
     * worker's claim: a job a claimer currently holds locked blocks here until
     * the claim commits, after which the new status decides the outcome; a
     * job this transaction holds locked is skipped by claimers via
     * {@code SKIP LOCKED}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM Job j WHERE j.id = :id")
    Optional<Job> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Claims up to {@code batchSize} due PENDING jobs. {@code FOR UPDATE SKIP
     * LOCKED} is the multi-worker guarantee: rows another claimer holds
     * locked are skipped rather than waited on, so concurrent workers claim
     * disjoint sets and never block each other. The row locks live for the
     * calling transaction, so a transaction is mandatory; the claimer flips
     * each row to RUNNING before committing, which is what makes the claim
     * durable once the locks release.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE status = 'PENDING'
                      AND scheduled_at <= :now
                    ORDER BY scheduled_at ASC, id ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<Job> lockClaimableJobs(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    /**
     * Locks RUNNING jobs whose lease deadline has passed, for the reaper.
     * {@code SKIP LOCKED} keeps concurrent reapers (one per node) on disjoint
     * rows and skips any row a worker's own completion transaction currently
     * holds — that completion, committing first, removes the row from this
     * result set on the next run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(
            value = """
                    SELECT *
                    FROM jobs
                    WHERE status = 'RUNNING'
                      AND lease_expires_at < :now
                    ORDER BY lease_expires_at ASC, id ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true
    )
    List<Job> lockExpiredLeases(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    /**
     * Heartbeat: one statement extends the lease of every RUNNING job the
     * worker holds. Filtering on {@code worker_id} makes it a no-op for jobs
     * the reaper already reclaimed — their claim columns are cleared, so a
     * zombie worker cannot resurrect a lease it lost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query("""
            UPDATE Job j
            SET j.leaseExpiresAt = :newDeadline, j.updatedAt = :now
            WHERE j.workerId = :workerId AND j.status = com.ahmetkeles.jobscheduler.domain.JobStatus.RUNNING
            """)
    int extendLeases(
            @Param("workerId") String workerId,
            @Param("newDeadline") Instant newDeadline,
            @Param("now") Instant now
    );

    List<Job> findByStatusOrderByCreatedAtDesc(JobStatus status, Pageable pageable);

    List<Job> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
