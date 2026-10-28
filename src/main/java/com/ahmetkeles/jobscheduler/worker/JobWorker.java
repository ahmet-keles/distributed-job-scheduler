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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The worker loop: poll, claim, execute, complete.
 *
 * <p>The poller claims at most {@code min(free handler threads, batch-size)}
 * jobs per poll (a semaphore tracks in-flight executions), so a full pool
 * never claims work it cannot start and one poll never exceeds the
 * configured batch. Execution happens on a dedicated pool, outside any transaction;
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
    private static final long CANCELLATION_POLL_INTERVAL_MS = 250;

    private final JobClaimService claimService;
    private final JobHandlerRegistry handlerRegistry;
    private final WorkerIdentity identity;
    private final WorkerProperties properties;
    private final ExecutorService executionPool;
    private final Semaphore freeSlots;
    private final ScheduledExecutorService timeoutScheduler;

    public JobWorker(
            JobClaimService claimService,
            JobHandlerRegistry handlerRegistry,
            WorkerIdentity identity,
            WorkerProperties properties
    ) {
        this.claimService = claimService;
        this.handlerRegistry = handlerRegistry;
        this.identity = identity;
        this.properties = properties;
        this.timeoutScheduler = Executors.newScheduledThreadPool(
                properties.getConcurrency(), runnable -> {
                    Thread thread = new Thread(runnable, "job-timeout-recorder");
                    thread.setDaemon(true);
                    return thread;
                });
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

        // One poll claims at most batch-size jobs, and never more than it
        // has free handler threads for — claimed-but-queued jobs would burn
        // lease time without running.
        int claimTarget = Math.min(slots, properties.getBatchSize());

        List<ClaimedJob> claimed;
        try {
            claimed = claimService.claimDueJobs(identity.id(), claimTarget);
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
        Optional<JobHandler> handler = handlerRegistry.find(claim.type());

        if (handler.isEmpty()) {
            recordFailure(claim, "no handler registered for job type '"
                    + claim.type() + "'");
            freeSlots.release();
            return;
        }

        AtomicBoolean outcomeRecorded = new AtomicBoolean();
        Thread handlerThread = Thread.currentThread();
        ScheduledFuture<?> timeoutTask = claim.timeoutSeconds() == null
                ? null
                : timeoutScheduler.schedule(() -> {
                    String error = "execution timed out after "
                            + claim.timeoutSeconds() + " seconds";
                    if (outcomeRecorded.compareAndSet(false, true)) {
                        handlerThread.interrupt();
                        recordFailure(claim, error);
                    }
                }, claim.timeoutSeconds(), TimeUnit.SECONDS);
        ScheduledFuture<?> cancellationTask = timeoutScheduler.scheduleWithFixedDelay(
                () -> pollCancellation(claim, handlerThread, outcomeRecorded),
                0,
                CANCELLATION_POLL_INTERVAL_MS,
                TimeUnit.MILLISECONDS);

        try {
            if (outcomeRecorded.get()) {
                return;
            }

            try {
                handler.get().execute(claim.payload());
            } catch (InterruptedException interrupted) {
                if (outcomeRecorded.compareAndSet(false, true)) {
                    recordFailure(claim, "execution interrupted");
                }
                return;
            } catch (Exception exception) {
                log.warn(
                        "Job {} attempt {} failed",
                        claim.jobId(), claim.attemptNumber(), exception);
                if (outcomeRecorded.compareAndSet(false, true)) {
                    recordFailure(claim, errorMessage(exception));
                }
                return;
            }

            if (outcomeRecorded.compareAndSet(false, true)) {
                claimService.recordSuccess(identity.id(), claim);
            }
        } catch (RuntimeException recordingFailure) {
            // The completion transaction itself failed. The job stays RUNNING
            // until its lease expires, at which point the reaper retries or
            // fails it — at-least-once semantics absorb the lost verdict.
            log.error(
                    "Failed to record outcome of job {} attempt {}; "
                            + "the lease reaper will reclaim it",
                    claim.jobId(), claim.attemptNumber(), recordingFailure);
        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            cancellationTask.cancel(false);
            // An interrupt used to stop a cancelled or timed-out handler must
            // not leak into the next task that reuses this pool thread.
            Thread.interrupted();
            freeSlots.release();
        }
    }

    private void pollCancellation(
            ClaimedJob claim,
            Thread handlerThread,
            AtomicBoolean outcomeRecorded
    ) {
        if (outcomeRecorded.get()) {
            return;
        }

        try {
            if (claimService.isCancellationRequested(claim)
                    && outcomeRecorded.compareAndSet(false, true)) {
                handlerThread.interrupt();
                claimService.recordCancellation(identity.id(), claim);
            }
        } catch (RuntimeException exception) {
            log.warn("Could not check cancellation request for job {} attempt {}",
                    claim.jobId(), claim.attemptNumber(), exception);
        }
    }

    private void recordFailure(ClaimedJob claim, String error) {
        try {
            claimService.recordFailure(identity.id(), claim, error);
        } catch (RuntimeException recordingFailure) {
            log.error("Failed to record failure of job {} attempt {}; "
                            + "the lease reaper will reclaim it",
                    claim.jobId(), claim.attemptNumber(), recordingFailure);
        }
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();

        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @PreDestroy
    void shutdown() {
        timeoutScheduler.shutdownNow();
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
