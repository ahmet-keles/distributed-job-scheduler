package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Priority is a claim-order preference among <em>due</em> jobs — higher
 * first, oldest first within a priority — and it must not weaken any of the
 * {@code SKIP LOCKED} guarantees.
 */
class PriorityClaimIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    private UUID newDueJob(int priority) {
        return jobService
                .createJob("noop", "{}", null, null, 1, priority)
                .getId();
    }

    @Test
    void claimsPreferHigherPriorityAmongDueJobs() {
        // Low-priority jobs are created FIRST, so age alone would pick them.
        List<UUID> low = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            low.add(newDueJob(0));
        }
        List<UUID> high = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            high.add(newDueJob(10));
        }

        Set<UUID> claimed = claimService.claimDueJobs("priority-worker", 3)
                .stream().map(ClaimedJob::jobId).collect(Collectors.toSet());

        // The batch may also sweep up leftovers from other tests; what
        // matters is that every high-priority job beat every low one.
        assertTrue(claimed.containsAll(high),
                "a batch of 3 must be exactly the 3 high-priority jobs");
        low.forEach(id -> assertEquals(JobStatus.PENDING,
                claimService.loadJob(id).getStatus(),
                "low-priority jobs must still be waiting"));

        drain("priority-worker");
    }

    @Test
    void equalPriorityFallsBackToOldestScheduledFirst() {
        // Explicit scheduled_at values: relying on creation order would make
        // the tiebreak depend on clock resolution.
        UUID older = jobService.createJob("noop", "{}", null,
                java.time.Instant.now().minusSeconds(120), 1, 5).getId();
        UUID newer = jobService.createJob("noop", "{}", null,
                java.time.Instant.now().minusSeconds(60), 1, 5).getId();

        List<ClaimedJob> claims =
                claimService.claimDueJobs("priority-worker", 1);

        assertEquals(1, claims.size());
        assertEquals(older, claims.get(0).jobId(),
                "within a priority, the oldest scheduled job runs first");
        assertEquals(JobStatus.PENDING,
                claimService.loadJob(newer).getStatus());

        drain("priority-worker");
    }

    @Test
    void aFutureHighPriorityJobNeverJumpsTheDueQueue() {
        UUID dueLow = newDueJob(0);
        UUID futureHigh = jobService
                .createJob("noop", "{}", 3600L, null, 1, 100)
                .getId();

        Set<UUID> claimed = claimService.claimDueJobs("priority-worker", 100)
                .stream().map(ClaimedJob::jobId).collect(Collectors.toSet());

        assertTrue(claimed.contains(dueLow),
                "the due job must be claimed regardless of priority");
        assertTrue(!claimed.contains(futureHigh),
                "priority must never make a future job due early");
        assertEquals(JobStatus.PENDING,
                claimService.loadJob(futureHigh).getStatus());

        drain("priority-worker");
    }

    @Test
    void concurrentClaimersSplitTheHighPriorityJobsDisjointly()
            throws Exception {
        Set<UUID> high = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            high.add(newDueJob(50));
        }
        Set<UUID> low = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            low.add(newDueJob(0));
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<List<ClaimedJob>> first = pool.submit(() -> {
                start.await();
                return claimService.claimDueJobs("priority-a", 2);
            });
            Future<List<ClaimedJob>> second = pool.submit(() -> {
                start.await();
                return claimService.claimDueJobs("priority-b", 2);
            });

            start.countDown();

            Set<UUID> byFirst = first.get(60, TimeUnit.SECONDS).stream()
                    .map(ClaimedJob::jobId).collect(Collectors.toSet());
            Set<UUID> bySecond = second.get(60, TimeUnit.SECONDS).stream()
                    .map(ClaimedJob::jobId).collect(Collectors.toSet());

            Set<UUID> overlap = new HashSet<>(byFirst);
            overlap.retainAll(bySecond);
            assertTrue(overlap.isEmpty(),
                    "SKIP LOCKED must keep concurrent claimers disjoint "
                            + "under priority ordering too");

            Set<UUID> union = new HashSet<>(byFirst);
            union.addAll(bySecond);
            assertEquals(high, union,
                    "the four claims across both workers must be exactly "
                            + "the four high-priority jobs");

            drain("priority-a");
            drain("priority-b");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Completes everything the worker holds, keeping the shared DB tidy. */
    private void drain(String workerId) {
        List<ClaimedJob> remaining;
        do {
            remaining = claimService.claimDueJobs(workerId, 100);
            remaining.forEach(claim ->
                    claimService.recordSuccess(workerId, claim));
        } while (!remaining.isEmpty());
    }
}
