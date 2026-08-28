package com.ahmetkeles.jobscheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A unit of work submitted through the API and executed by exactly one worker
 * at a time.
 *
 * <p>Concurrency control is row-level, not optimistic: every transition runs
 * under a database row lock. Workers claim PENDING jobs with
 * {@code FOR UPDATE SKIP LOCKED}, and the API's cancel path loads the row with
 * a pessimistic write lock, so a claim and a cancel of the same job serialize
 * at the database — whichever commits first, the other observes its outcome
 * and acts on the new state.
 *
 * <p>While RUNNING, the job carries the claiming worker's id and a lease
 * deadline. A live worker keeps extending the lease; a lease found expired
 * means the worker died (or lost connectivity), and the reaper returns the
 * job to PENDING for another attempt — execution is therefore at-least-once,
 * and handlers must tolerate re-execution.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String type;

    /** Handler input, an opaque JSON document from the client's perspective. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    /** Earliest instant the job may be claimed; now() for immediate jobs. */
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    /** Execution attempts started so far (claimed counts, even if abandoned). */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    /** Identity of the worker holding the current claim; null unless RUNNING. */
    @Column(name = "worker_id", length = 100)
    private String workerId;

    /** Claim lease deadline; null unless RUNNING. */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    /** Error message of the most recent failed or abandoned attempt. */
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Job() {
    }

    public Job(String type, String payload, Instant scheduledAt, int maxAttempts) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }

        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt is required");
        }

        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }

        this.id = UUID.randomUUID();
        this.type = type;
        this.payload = payload == null || payload.isBlank() ? "{}" : payload;
        this.status = JobStatus.PENDING;
        this.scheduledAt = scheduledAt;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Claims the job for a worker: PENDING -> RUNNING, one more attempt
     * started, lease opened. Only ever called on a row the claiming
     * transaction holds locked, so the PENDING check cannot race.
     */
    public void startAttempt(String claimingWorkerId, Instant leaseDeadline) {
        if (status != JobStatus.PENDING) {
            throw new IllegalStateException(
                    "Job " + id + " cannot start an attempt in status " + status);
        }

        status = JobStatus.RUNNING;
        attempts++;
        workerId = claimingWorkerId;
        leaseExpiresAt = leaseDeadline;
        updatedAt = Instant.now();
    }

    /**
     * Records a successful attempt by the given worker. Returns false without
     * touching state when the claim is no longer this worker's — the lease
     * expired and the reaper already reclaimed the job — so a zombie worker
     * can never overwrite a state someone else now owns.
     */
    public boolean succeed(String completingWorkerId) {
        if (!holdsClaim(completingWorkerId)) {
            return false;
        }

        status = JobStatus.SUCCEEDED;
        clearClaim();
        return true;
    }

    /**
     * Records a failed attempt by the given worker: back to PENDING at
     * {@code nextRunAt} while attempts remain, terminal FAILED once they are
     * exhausted. Returns false without touching state when the claim is no
     * longer this worker's (see {@link #succeed}).
     */
    public boolean fail(String failingWorkerId, String error, Instant nextRunAt) {
        if (!holdsClaim(failingWorkerId)) {
            return false;
        }

        applyFailure(error, nextRunAt);
        return true;
    }

    /**
     * Reaper transition for an expired lease: the worker stopped heartbeating,
     * so its attempt is written off and the job is retried or failed exactly
     * like an ordinary failure. Only called on a locked RUNNING row.
     */
    public void expireLease(String error, Instant nextRunAt) {
        if (status != JobStatus.RUNNING) {
            throw new IllegalStateException(
                    "Job " + id + " has no lease to expire in status " + status);
        }

        applyFailure(error, nextRunAt);
    }

    private void applyFailure(String error, Instant nextRunAt) {
        lastError = error;

        if (attempts >= maxAttempts) {
            status = JobStatus.FAILED;
            clearClaim();
            return;
        }

        status = JobStatus.PENDING;
        scheduledAt = nextRunAt;
        clearClaim();
    }

    /**
     * Cancels a PENDING job. Any other status throws: RUNNING is already on a
     * worker, and terminal states have nothing left to cancel.
     */
    public void cancel() {
        if (status != JobStatus.PENDING) {
            throw new JobNotCancellableException(id, status);
        }

        status = JobStatus.CANCELLED;
        updatedAt = Instant.now();
    }

    /** Extends the lease; a no-op unless this worker still holds the claim. */
    public void extendLease(String heartbeatingWorkerId, Instant newDeadline) {
        if (!holdsClaim(heartbeatingWorkerId)) {
            return;
        }

        leaseExpiresAt = newDeadline;
        updatedAt = Instant.now();
    }

    private boolean holdsClaim(String candidateWorkerId) {
        return status == JobStatus.RUNNING
                && workerId != null
                && workerId.equals(candidateWorkerId);
    }

    private void clearClaim() {
        workerId = null;
        leaseExpiresAt = null;
        updatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return status == JobStatus.SUCCEEDED
                || status == JobStatus.FAILED
                || status == JobStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
