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

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** Test seam: collapses a backed-off PENDING job's delay to "due now". */
    private void rescheduleNow(UUID jobId) {
        int updated = jdbcTemplate.update(
                "UPDATE jobs SET scheduled_at = now() "
                        + "WHERE id = ? AND status = 'PENDING'",
                jobId);
        assertEquals(1, updated, "job must be PENDING to reschedule");
    }

    /**
     * The attempt-fencing race from the review: the SAME worker process loses
     * attempt 1 to the reaper, claims attempt 2 of the same job on another
     * execution slot, and then attempt 1's verdict arrives late. Matching on
     * worker id alone would let that stale verdict complete attempt 2; the
     * attempt-number fence must discard it.
     */
    @Test
    void aStaleAttemptsVerdictCannotCompleteTheCurrentAttempt()
            throws Exception {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 3).getId();

        // Attempt 1: claimed by worker-a with an instantly-expired lease,
        // then reaped back to PENDING with the attempt written off.
        ClaimedJob staleClaim = claimWithExpiredLease("worker-a", jobId);
        claimService.requeueExpiredLeases();
        assertEquals(JobStatus.PENDING,
                claimService.loadJob(jobId).getStatus());
        assertEquals(AttemptOutcome.ABANDONED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome());

        // Attempt 2: the retry becomes due and the SAME worker-a claims it.
        rescheduleNow(jobId);
        ClaimedJob currentClaim = claimService
                .claimDueJobs("worker-a", 100).stream()
                .filter(candidate -> candidate.jobId().equals(jobId))
                .findFirst().orElseThrow();
        assertEquals(2, currentClaim.attemptNumber());

        // Attempt 1 finishes late — success AND failure must both bounce off
        // the fence without touching job state or attempt history.
        claimService.recordSuccess("worker-a", staleClaim);
        Job afterStaleSuccess = claimService.loadJob(jobId);
        assertEquals(JobStatus.RUNNING, afterStaleSuccess.getStatus(),
                "attempt 2 must remain RUNNING after attempt 1's late success");
        assertEquals(2, afterStaleSuccess.getAttempts());
        assertEquals("worker-a", afterStaleSuccess.getWorkerId());

        claimService.recordFailure("worker-a", staleClaim, "late failure");
        assertEquals(JobStatus.RUNNING,
                claimService.loadJob(jobId).getStatus(),
                "attempt 2 must remain RUNNING after attempt 1's late failure");

        assertEquals(AttemptOutcome.ABANDONED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome(), "attempt 1's history keeps the reaper's verdict");
        assertTrue(attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 2).orElseThrow()
                .isOpen(), "attempt 2's history row must stay open");

        // The current attempt then completes normally.
        claimService.recordSuccess("worker-a", currentClaim);
        assertEquals(JobStatus.SUCCEEDED,
                claimService.loadJob(jobId).getStatus());
        assertEquals(AttemptOutcome.SUCCEEDED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 2).orElseThrow()
                .getOutcome());
    }

    /**
     * A heartbeat arriving after the lease deadline must not resurrect the
     * lease: the job already belongs to crash recovery.
     */
    @Test
    void aLateHeartbeatCannotResurrectAnExpiredLease() throws Exception {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 3).getId();

        claimWithExpiredLease("late-heartbeater", jobId);

        assertEquals(0, claimService.heartbeat("late-heartbeater"),
                "an expired lease must extend zero rows");

        assertTrue(claimService.requeueExpiredLeases() >= 1,
                "the reaper must still find the expired lease");
        assertEquals(JobStatus.PENDING,
                claimService.loadJob(jobId).getStatus(),
                "the job must be reclaimed despite the late heartbeat");
        assertEquals(AttemptOutcome.ABANDONED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome());
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
