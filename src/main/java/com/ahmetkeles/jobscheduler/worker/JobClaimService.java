package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.domain.AttemptOutcome;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobAttempt;
import com.ahmetkeles.jobscheduler.repository.JobAttemptRepository;
import com.ahmetkeles.jobscheduler.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The transactional core of the worker: claim, complete, fail, and reap.
 *
 * <p>Each method is one short transaction over row-locked jobs. Execution of
 * the handler itself happens strictly <em>between</em> the claim transaction
 * and the completion transaction, never inside either — a slow handler must
 * not pin row locks or a connection.
 *
 * <p>Completion is fenced on the claim's worker id AND attempt number: if
 * the worker's lease expired mid-execution and the reaper already reclaimed
 * the job, the late completion finds the fence mismatched and records nothing
 * on the job (the attempt row keeps its ABANDONED verdict) — even when the
 * same worker process has since claimed the job's next attempt. The fence
 * bounds the blast radius of a paused or partitioned worker to "a stale
 * verdict is discarded"; note that the handler itself may still have executed
 * (possibly concurrently with the replacement attempt), which is why
 * execution is at-least-once and handlers must be idempotent.
 */
@Service
public class JobClaimService {

    private static final Logger log =
            LoggerFactory.getLogger(JobClaimService.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final WorkerProperties properties;
    private final RetryPolicy retryPolicy;

    public JobClaimService(
            JobRepository jobRepository,
            JobAttemptRepository jobAttemptRepository,
            WorkerProperties properties,
            RetryPolicy retryPolicy
    ) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
    }

    /**
     * Claims up to {@code batchSize} due jobs for {@code workerId}: locks
     * PENDING rows with {@code FOR UPDATE SKIP LOCKED}, flips each to RUNNING
     * with a fresh lease, and opens its attempt row — all in one transaction,
     * so a crash before commit leaves the jobs untouched and unclaimed.
     */
    @Transactional
    public List<ClaimedJob> claimDueJobs(String workerId, int batchSize) {
        Instant now = Instant.now();
        Instant leaseDeadline = now.plus(properties.getLeaseDuration());

        List<Job> locked = jobRepository.lockClaimableJobs(now, batchSize);
        List<ClaimedJob> claimed = new ArrayList<>(locked.size());

        for (Job job : locked) {
            job.startAttempt(workerId, leaseDeadline);

            jobAttemptRepository.save(
                    new JobAttempt(job.getId(), job.getAttempts(), workerId));

            claimed.add(new ClaimedJob(
                    job.getId(),
                    job.getType(),
                    job.getPayload(),
                    job.getAttempts()
            ));
        }

        return claimed;
    }

    /** Records a successful attempt, unless the claim was lost meanwhile. */
    @Transactional
    public void recordSuccess(String workerId, ClaimedJob claim) {
        Job job = jobRepository.findByIdForUpdate(claim.jobId()).orElseThrow();

        if (!job.succeed(workerId, claim.attemptNumber())) {
            log.warn(
                    "Worker {} finished job {} attempt {} after losing the "
                            + "claim; result discarded, job stays {}",
                    workerId, claim.jobId(), claim.attemptNumber(),
                    job.getStatus());
            return;
        }

        closeAttempt(claim, AttemptOutcome.SUCCEEDED, null);
    }

    /**
     * Records a failed attempt: retry with exponential backoff while attempts
     * remain, terminal FAILED once exhausted — unless the claim was lost
     * meanwhile, in which case the reaper's verdict stands.
     */
    @Transactional
    public void recordFailure(String workerId, ClaimedJob claim, String error) {
        Job job = jobRepository.findByIdForUpdate(claim.jobId()).orElseThrow();

        Instant nextRunAt = Instant.now()
                .plus(retryPolicy.backoffAfter(claim.attemptNumber()));

        if (!job.fail(workerId, claim.attemptNumber(), error, nextRunAt)) {
            log.warn(
                    "Worker {} failed job {} attempt {} after losing the "
                            + "claim; job stays {}",
                    workerId, claim.jobId(), claim.attemptNumber(),
                    job.getStatus());
            return;
        }

        closeAttempt(claim, AttemptOutcome.FAILED, error);
    }

    /**
     * Reaper sweep: every RUNNING job whose lease deadline has passed gets its
     * open attempt closed as ABANDONED and is retried (with backoff) or
     * failed, exactly like an ordinary failure. Safe to run on every node;
     * {@code SKIP LOCKED} partitions the expired rows between sweepers.
     *
     * @return how many expired leases were reclaimed
     */
    @Transactional
    public int requeueExpiredLeases() {
        Instant now = Instant.now();

        List<Job> expired = jobRepository.lockExpiredLeases(
                now, properties.getReaperBatchSize());

        for (Job job : expired) {
            String error = "lease expired: worker " + job.getWorkerId()
                    + " stopped heartbeating";

            jobAttemptRepository
                    .findByJobIdAndAttemptNumber(job.getId(), job.getAttempts())
                    .filter(JobAttempt::isOpen)
                    .ifPresent(attempt ->
                            attempt.finish(AttemptOutcome.ABANDONED, error));

            Instant nextRunAt =
                    now.plus(retryPolicy.backoffAfter(job.getAttempts()));

            job.expireLease(error, nextRunAt);

            log.warn(
                    "Reclaimed job {} attempt {} from expired lease; job is now {}",
                    job.getId(), job.getAttempts(), job.getStatus());
        }

        return expired.size();
    }

    /**
     * Extends the lease of every RUNNING job this worker holds, in one
     * statement. Jobs the reaper already reclaimed no longer carry this
     * worker's id, so a heartbeat can never resurrect a lost lease.
     *
     * @return how many leases were extended
     */
    @Transactional
    public int heartbeat(String workerId) {
        Instant now = Instant.now();

        return jobRepository.extendLeases(
                workerId,
                now.plus(properties.getLeaseDuration()),
                now
        );
    }

    private void closeAttempt(
            ClaimedJob claim,
            AttemptOutcome outcome,
            String error
    ) {
        JobAttempt attempt = jobAttemptRepository
                .findByJobIdAndAttemptNumber(
                        claim.jobId(), claim.attemptNumber())
                .orElseThrow(() -> new IllegalStateException(
                        "No attempt row for job " + claim.jobId()
                                + " attempt " + claim.attemptNumber()));

        attempt.finish(outcome, error);
    }

    /** Test seam: loads a job in a fresh transaction. */
    @Transactional(readOnly = true)
    public Job loadJob(UUID jobId) {
        return jobRepository.findById(jobId).orElseThrow();
    }
}
