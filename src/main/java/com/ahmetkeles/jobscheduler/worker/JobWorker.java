package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.worker.handler.JobHandler;
import com.ahmetkeles.jobscheduler.worker.handler.JobHandlerRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The worker loop: poll, claim, execute, complete.
 *
 * <p>The poller claims at most as many jobs as it has free handler threads
 * (a semaphore tracks in-flight executions), so a full pool never claims work
 * it cannot start — claimed-but-queued jobs would burn lease time without
 * running. Execution happens on a dedicated pool, outside any transaction;
 * completion and failure are recorded by {@link JobClaimService} in their own
 * short transactions.
 *
 * <p>The heartbeat runs on the scheduler thread pool independently of the
 * handlers, so a slow handler cannot starve the lease renewal that keeps its
 * own job claimed.
 */
@Component
@ConditionalOnProperty(
        name = "app.worker.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobClaimService claimService;
    private final JobHandlerRegistry handlerRegistry;
    private final WorkerIdentity identity;
    private final ExecutorService executionPool;
    private final Semaphore freeSlots;

    public JobWorker(
            JobClaimService claimService,
            JobHandlerRegistry handlerRegistry,
            WorkerIdentity identity,
            WorkerProperties properties
    ) {
        this.claimService = claimService;
        this.handlerRegistry = handlerRegistry;
        this.identity = identity;
        this.executionPool =
                Executors.newFixedThreadPool(properties.getConcurrency());
        this.freeSlots = new Semaphore(properties.getConcurrency());

        log.info(
                "Worker {} started: handlers {}, concurrency {}",
                identity.id(),
                handlerRegistry.registeredTypes(),
                properties.getConcurrency()
        );
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-interval-ms:1000}")
    public void poll() {
        int slots = freeSlots.drainPermits();

        if (slots == 0) {
            return;
        }

        List<ClaimedJob> claimed;
        try {
            claimed = claimService.claimDueJobs(identity.id(), slots);
        } catch (RuntimeException exception) {
            freeSlots.release(slots);
            log.error("Claim poll failed; will retry next interval", exception);
            return;
        }

        // Unused slots go straight back; used ones return when execution ends.
        freeSlots.release(slots - claimed.size());

        for (ClaimedJob claim : claimed) {
            executionPool.submit(() -> execute(claim));
        }
    }

    @Scheduled(fixedDelayString = "${app.worker.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        int extended = claimService.heartbeat(identity.id());

        if (extended > 0) {
            log.debug("Extended {} lease(s) for worker {}", extended, identity.id());
        }
    }

    private void execute(ClaimedJob claim) {
        try {
            Optional<JobHandler> handler = handlerRegistry.find(claim.type());

            if (handler.isEmpty()) {
                claimService.recordFailure(
                        identity.id(),
                        claim,
                        "no handler registered for job type '"
                                + claim.type() + "'");
                return;
            }

            try {
                handler.get().execute(claim.payload());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                claimService.recordFailure(
                        identity.id(), claim, "execution interrupted");
                return;
            } catch (Exception exception) {
                log.warn(
                        "Job {} attempt {} failed",
                        claim.jobId(), claim.attemptNumber(), exception);
                claimService.recordFailure(
                        identity.id(), claim, errorMessage(exception));
                return;
            }

            claimService.recordSuccess(identity.id(), claim);
        } catch (RuntimeException recordingFailure) {
            // The completion transaction itself failed. The job stays RUNNING
            // until its lease expires, at which point the reaper retries or
            // fails it — at-least-once semantics absorb the lost verdict.
            log.error(
                    "Failed to record outcome of job {} attempt {}; "
                            + "the lease reaper will reclaim it",
                    claim.jobId(), claim.attemptNumber(), recordingFailure);
        } finally {
            freeSlots.release();
        }
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();

        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @PreDestroy
    void shutdown() {
        executionPool.shutdown();

        try {
            if (!executionPool.awaitTermination(10, TimeUnit.SECONDS)) {
                executionPool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executionPool.shutdownNow();
        }
    }
}
