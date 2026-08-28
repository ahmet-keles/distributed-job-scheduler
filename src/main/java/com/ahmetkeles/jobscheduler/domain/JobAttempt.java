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
 * Execution history: one row per attempt a worker started, written in the
 * same transaction as the claim. An attempt is open (no outcome) while the
 * worker executes; it is closed by the worker with SUCCEEDED or FAILED, or by
 * the reaper with ABANDONED when the worker's lease expired first. Attempt
 * rows are immutable once closed and are never deleted in this milestone.
 */
@Entity
@Table(name = "job_attempts")
public class JobAttempt {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    /** 1-based; equals the job's {@code attempts} counter at claim time. */
    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", nullable = false, length = 100)
    private String workerId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AttemptOutcome outcome;

    @Column(columnDefinition = "text")
    private String error;

    protected JobAttempt() {
    }

    public JobAttempt(UUID jobId, int attemptNumber, String workerId) {
        this.id = UUID.randomUUID();
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.workerId = workerId;
        this.startedAt = Instant.now();
    }

    /** Closes the attempt; a second close is rejected to keep history immutable. */
    public void finish(AttemptOutcome finalOutcome, String finalError) {
        if (outcome != null) {
            throw new IllegalStateException(
                    "Attempt " + id + " is already closed as " + outcome);
        }

        this.outcome = finalOutcome;
        this.error = finalError;
        this.finishedAt = Instant.now();
    }

    public boolean isOpen() {
        return outcome == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public String getError() {
        return error;
    }
}
