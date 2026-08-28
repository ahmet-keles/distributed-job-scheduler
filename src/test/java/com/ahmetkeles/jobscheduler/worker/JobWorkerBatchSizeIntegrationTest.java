package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.service.JobService;
import com.ahmetkeles.jobscheduler.worker.handler.JobHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One poll claims at most {@code min(free handler threads, batch-size)}:
 * with ten free threads, a batch size of three, and seven due jobs, the
 * first poll must claim exactly three — not everything the pool could hold.
 */
class JobWorkerBatchSizeIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JobHandlerRegistry handlerRegistry;

    @Test
    void onePollClaimsAtMostTheConfiguredBatchSize() {
        Set<UUID> seeded = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            seeded.add(jobService.createJob(
                    "sleep", "{\"millis\": 1000}", null, null, 1).getId());
        }

        WorkerProperties properties = new WorkerProperties();
        properties.setConcurrency(10);
        properties.setBatchSize(3);

        JobWorker worker = new JobWorker(
                claimService,
                handlerRegistry,
                new WorkerIdentity("batch-test"),
                properties
        );

        try {
            worker.poll();

            // Claims commit inside poll(); the sleeping handlers keep the
            // claimed jobs RUNNING while we count.
            long claimed = seeded.stream()
                    .map(claimService::loadJob)
                    .filter(job -> job.getStatus() != JobStatus.PENDING)
                    .count();

            assertEquals(3, claimed,
                    "with 10 free threads and batch-size 3, one poll must "
                            + "claim exactly 3 of the 7 due jobs");

            awaitAllSettled(seeded);
        } finally {
            worker.shutdown();
        }

        // Leave the shared database tidy for the other test classes.
        seeded.stream()
                .map(claimService::loadJob)
                .filter(job -> job.getStatus() == JobStatus.PENDING)
                .forEach(job -> jobService.cancelJob(job.getId()));
    }

    /** Waits until no seeded job is still RUNNING (claimed work finished). */
    private void awaitAllSettled(Set<UUID> seeded) {
        long deadline = System.currentTimeMillis()
                + Duration.ofSeconds(30).toMillis();

        while (System.currentTimeMillis() < deadline) {
            boolean anyRunning = seeded.stream()
                    .map(claimService::loadJob)
                    .anyMatch(job -> job.getStatus() == JobStatus.RUNNING);

            if (!anyRunning) {
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for claimed jobs to finish");
            }
        }

        fail("claimed jobs did not finish executing");
    }
}
