package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.AttemptOutcome;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobAttempt;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.repository.JobAttemptRepository;
import com.ahmetkeles.jobscheduler.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim/complete/fail contract against real PostgreSQL row locking. The
 * background worker is disabled (see the base class); every transition here
 * is driven explicitly.
 */
class JobClaimIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JobAttemptRepository attemptRepository;

    private UUID newDueJob(int maxAttempts) {
        return jobService
                .createJob("noop", "{}", null, null, maxAttempts)
                .getId();
    }

    private ClaimedJob claimOne(String workerId, UUID jobId) {
        return claimService.claimDueJobs(workerId, 100).stream()
                .filter(claim -> claim.jobId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "job " + jobId + " was not claimed"));
    }

    @Test
    void claimingFlipsTheJobToRunningWithALeaseAndAnOpenAttempt() {
        UUID jobId = newDueJob(3);

        ClaimedJob claim = claimOne("worker-a", jobId);

        assertEquals(1, claim.attemptNumber());

        Job job = claimService.loadJob(jobId);
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertEquals("worker-a", job.getWorkerId());
        assertNotNull(job.getLeaseExpiresAt());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()),
                "a fresh claim must carry a live lease");

        JobAttempt attempt = attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow();
        assertTrue(attempt.isOpen());
        assertEquals("worker-a", attempt.getWorkerId());
    }

    @Test
    void delayedJobsAreNotClaimableBeforeTheirScheduledTime() {
        UUID delayed = jobService
                .createJob("noop", "{}", 3600L, null, null)
                .getId();

        List<ClaimedJob> claimed = claimService.claimDueJobs("worker-a", 100);

        assertTrue(claimed.stream().noneMatch(
                        claim -> claim.jobId().equals(delayed)),
                "a job scheduled an hour out must not be claimed now");
        assertEquals(JobStatus.PENDING,
                claimService.loadJob(delayed).getStatus());
    }

    @Test
    void cancelledJobsAreNeverClaimed() {
        UUID cancelled = jobService
                .createJob("noop", "{}", null, null, null)
                .getId();
        jobService.cancelJob(cancelled);

        List<ClaimedJob> claimed = claimService.claimDueJobs("worker-a", 100);

        assertTrue(claimed.stream().noneMatch(
                claim -> claim.jobId().equals(cancelled)));
    }

    @Test
    void concurrentWorkersClaimDisjointJobs() throws Exception {
        Set<UUID> seeded = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            seeded.add(newDueJob(3));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<List<ClaimedJob>> first = pool.submit(() -> {
                start.await();
                return claimService.claimDueJobs("worker-a", 100);
            });
            Future<List<ClaimedJob>> second = pool.submit(() -> {
                start.await();
                return claimService.claimDueJobs("worker-b", 100);
            });

            start.countDown();

            Set<UUID> byFirst = first.get(60, TimeUnit.SECONDS).stream()
                    .map(ClaimedJob::jobId).collect(Collectors.toSet());
            Set<UUID> bySecond = second.get(60, TimeUnit.SECONDS).stream()
                    .map(ClaimedJob::jobId).collect(Collectors.toSet());

            Set<UUID> overlap = new HashSet<>(byFirst);
            overlap.retainAll(bySecond);
            assertTrue(overlap.isEmpty(),
                    "SKIP LOCKED must keep concurrent claimers on "
                            + "disjoint jobs, but both claimed: " + overlap);

            Set<UUID> union = new HashSet<>(byFirst);
            union.addAll(bySecond);
            assertTrue(union.containsAll(seeded),
                    "every seeded job must be claimed by exactly one worker");

            for (UUID jobId : seeded) {
                assertEquals(JobStatus.RUNNING,
                        claimService.loadJob(jobId).getStatus());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void successIsRecordedOnJobAndAttempt() {
        UUID jobId = newDueJob(3);
        ClaimedJob claim = claimOne("worker-a", jobId);

        claimService.recordSuccess("worker-a", claim);

        Job job = claimService.loadJob(jobId);
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertNull(job.getWorkerId());
        assertNull(job.getLeaseExpiresAt());

        JobAttempt attempt = attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow();
        assertEquals(AttemptOutcome.SUCCEEDED, attempt.getOutcome());
        assertNotNull(attempt.getFinishedAt());
    }

    @Test
    void failureWithAttemptsLeftRequeuesWithBackoff() {
        UUID jobId = newDueJob(3);
        ClaimedJob claim = claimOne("worker-a", jobId);

        Instant before = Instant.now();
        claimService.recordFailure("worker-a", claim, "boom");

        Job job = claimService.loadJob(jobId);
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertEquals("boom", job.getLastError());
        assertTrue(job.getScheduledAt().isAfter(before),
                "the retry must be pushed into the future by the backoff");

        assertEquals(AttemptOutcome.FAILED, attemptRepository
                .findByJobIdAndAttemptNumber(jobId, 1).orElseThrow()
                .getOutcome());
    }

    @Test
    void failureOnTheLastAttemptIsTerminalWithFullHistory() {
        UUID jobId = newDueJob(2);

        ClaimedJob first = claimOne("worker-a", jobId);
        claimService.recordFailure("worker-a", first, "first failure");

        // Requeued with backoff; make it due again for the second claim.
        rescheduleNow(jobId);
        ClaimedJob second = claimOne("worker-b", jobId);
        assertEquals(2, second.attemptNumber());
        claimService.recordFailure("worker-b", second, "second failure");

        Job job = claimService.loadJob(jobId);
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("second failure", job.getLastError());

        List<JobAttempt> history =
                attemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
        assertEquals(2, history.size());
        assertEquals(AttemptOutcome.FAILED, history.get(0).getOutcome());
        assertEquals(AttemptOutcome.FAILED, history.get(1).getOutcome());
        assertEquals("worker-a", history.get(0).getWorkerId());
        assertEquals("worker-b", history.get(1).getWorkerId());
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
}
