package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.AttemptOutcome;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.repository.JobAttemptRepository;
import com.ahmetkeles.jobscheduler.repository.JobRepository;
import com.ahmetkeles.jobscheduler.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash recovery: a worker that stops heartbeating loses its claim, the open
 * attempt is written off as ABANDONED, and the job is retried or failed. The
 * "crashed worker" is simulated with a claim service whose lease duration is
 * one millisecond — the lease is expired the moment it is granted.
 */
class ExpiredLeaseReaperIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobAttemptRepository attemptRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    /**
     * Runs one call of the hand-built (unproxied) claim service inside a
     * transaction, standing in for the {@code @Transactional} proxy the Spring
     * bean would have.
     */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support
                .TransactionTemplate(txManager)
                .execute(status -> work.get());
    }

    /** A claim service whose every lease is already expired at grant time. */
    private JobClaimService crashingClaimService() {
        WorkerProperties instant = new WorkerProperties();
        instant.setLeaseDuration(Duration.ofMillis(1));
        instant.setRetryInitialBackoff(Duration.ofMillis(1));

        return new JobClaimService(
                jobRepository,
                attemptRepository,
                instant,
                new RetryPolicy(instant)
        );
    }

    private ClaimedJob claimWithExpiredLease(String workerId, UUID jobId)
            throws InterruptedException {
        JobClaimService crashing = crashingClaimService();
        ClaimedJob claim = inTransaction(
                () -> crashing.claimDueJobs(workerId, 100)).stream()
                .filter(candidate -> candidate.jobId().equals(jobId))
                .findFirst().orElseThrow();

        Thread.sleep(5); // outlive the 1ms lease
        return claim;
    }

    @Test
    void expiredLeaseIsReclaimedAndTheAttemptAbandoned() throws Exception {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 3).getId();

        claimWithExpiredLease("crashed-worker", jobId);

        int reclaimed = claimService.requeueExpiredLeases();
        assertTrue(reclaimed >= 1);

        Job job = claimService.loadJob(jobId);
        assertEquals(JobStatus.PENDING, job.getStatus(),
                "attempts remained, so the job must be retryable again");
        assertEquals(1, job.getAttempts());
        assertNotNull(job.getLastError());
        assertTrue(job.getLastError().contains("lease expired"));

        assertEquals(AttemptOutcome.ABANDONED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome());
    }

    @Test
    void expiredLeaseOnTheLastAttemptFailsTheJob() throws Exception {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 1).getId();

        claimWithExpiredLease("crashed-worker", jobId);

        claimService.requeueExpiredLeases();

        assertEquals(JobStatus.FAILED,
                claimService.loadJob(jobId).getStatus());
        assertEquals(AttemptOutcome.ABANDONED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome());
    }

    @Test
    void lateCompletionAfterReclaimIsDiscarded() throws Exception {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 3).getId();

        JobClaimService crashed = crashingClaimService();
        ClaimedJob claim = claimWithExpiredLease("zombie-worker", jobId);

        claimService.requeueExpiredLeases();
        assertEquals(JobStatus.PENDING,
                claimService.loadJob(jobId).getStatus());

        // The "crashed" worker was only paused; it now reports success.
        inTransaction(() -> {
            crashed.recordSuccess("zombie-worker", claim);
            return null;
        });

        Job job = claimService.loadJob(jobId);
        assertEquals(JobStatus.PENDING, job.getStatus(),
                "a zombie's late verdict must not overwrite the reaper's");
        assertEquals(AttemptOutcome.ABANDONED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome(),
                "the abandoned attempt keeps its verdict");
    }

    @Test
    void heartbeatExtendsOnlyTheOwnersLiveLeases() {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 3).getId();

        java.util.List<ClaimedJob> claims =
                claimService.claimDueJobs("live-worker", 100);
        assertTrue(claims.stream()
                        .anyMatch(candidate -> candidate.jobId().equals(jobId)),
                "the seeded job must be among the claims");

        Instant initialDeadline =
                claimService.loadJob(jobId).getLeaseExpiresAt();

        assertEquals(0, claimService.heartbeat("someone-else"),
                "another worker's heartbeat must not touch this lease");

        int extended = claimService.heartbeat("live-worker");
        assertTrue(extended >= 1);

        Instant newDeadline = claimService.loadJob(jobId).getLeaseExpiresAt();
        assertTrue(newDeadline.isAfter(initialDeadline),
                "the owner's heartbeat must push the deadline forward");

        // Complete everything this worker claimed: with no RUNNING claims
        // left, its heartbeat has nothing to extend.
        claims.forEach(claim ->
                claimService.recordSuccess("live-worker", claim));
        assertEquals(0, claimService.heartbeat("live-worker"),
                "a worker with no RUNNING claims holds no lease to extend");
    }
}
