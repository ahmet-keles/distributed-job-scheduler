package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.domain.AttemptOutcome;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobAttempt;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.repository.JobAttemptRepository;
import com.ahmetkeles.jobscheduler.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The full scheduled loop, worker enabled: jobs submitted through the service
 * are picked up by the poller, executed by real handlers, and completed —
 * including the retry path — with no manual driving. Intervals and backoff
 * are shrunk so the whole loop settles in well under a second per hop.
 */
@SpringBootTest
class JobWorkerEndToEndTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("app.worker.enabled", () -> "true");
        registry.add("app.worker.id", () -> "e2e-worker");
        registry.add("app.worker.poll-interval-ms", () -> "100");
        registry.add("app.worker.heartbeat-interval-ms", () -> "200");
        registry.add("app.worker.reaper-interval-ms", () -> "200");
        registry.add("app.worker.retry-initial-backoff", () -> "100ms");
        registry.add("app.worker.retry-max-backoff", () -> "200ms");
    }

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JobAttemptRepository attemptRepository;

    @Test
    void aSubmittedJobIsClaimedExecutedAndSucceeds() {
        UUID jobId = jobService
                .createJob("noop", "{}", null, null, 3).getId();

        awaitStatus(jobId, JobStatus.SUCCEEDED);

        Job job = claimService.loadJob(jobId);
        assertEquals(1, job.getAttempts());

        List<JobAttempt> history =
                attemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
        assertEquals(1, history.size());
        assertEquals(AttemptOutcome.SUCCEEDED, history.get(0).getOutcome());
        assertEquals("e2e-worker", history.get(0).getWorkerId());
    }

    @Test
    void aFailingJobIsRetriedAndEndsUpFailedWithFullHistory() {
        UUID jobId = jobService.createJob(
                "always-fail",
                "{\"message\": \"scripted failure\"}",
                null, null, 2
        ).getId();

        awaitStatus(jobId, JobStatus.FAILED);

        Job job = claimService.loadJob(jobId);
        assertEquals(2, job.getAttempts());
        assertTrue(job.getLastError().contains("scripted failure"));

        List<JobAttempt> history =
                attemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
        assertEquals(2, history.size());
        history.forEach(attempt ->
                assertEquals(AttemptOutcome.FAILED, attempt.getOutcome()));
    }

    @Test
    void aJobWithNoRegisteredHandlerFails() {
        UUID jobId = jobService
                .createJob("no-such-type", "{}", null, null, 1).getId();

        awaitStatus(jobId, JobStatus.FAILED);

        assertTrue(claimService.loadJob(jobId).getLastError()
                .contains("no handler registered"));
    }

    @Test
    void aDelayedJobStaysPendingUntilDue() throws Exception {
        UUID jobId = jobService
                .createJob("noop", "{}", 3600L, null, null).getId();

        Thread.sleep(500); // several poll intervals

        assertEquals(JobStatus.PENDING,
                claimService.loadJob(jobId).getStatus(),
                "a job due in an hour must not be claimed by the poller");
    }

    private void awaitStatus(UUID jobId, JobStatus expected) {
        awaitTrue(
                () -> claimService.loadJob(jobId).getStatus() == expected,
                Duration.ofSeconds(20),
                "job " + jobId + " did not reach " + expected
                        + " (currently "
                        + claimService.loadJob(jobId).getStatus() + ")"
        );
    }

    private static void awaitTrue(
            Supplier<Boolean> condition, Duration timeout, String message) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted: " + message);
            }
        }

        fail(message);
    }
}
