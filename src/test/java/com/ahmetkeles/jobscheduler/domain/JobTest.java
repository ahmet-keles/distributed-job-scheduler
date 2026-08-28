package com.ahmetkeles.jobscheduler.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobTest {

    private static final Instant NOW = Instant.now();
    private static final Instant LEASE = NOW.plus(30, ChronoUnit.SECONDS);
    private static final Instant RETRY_AT = NOW.plus(5, ChronoUnit.SECONDS);

    private static Job newJob(int maxAttempts) {
        return new Job("noop", "{}", NOW, maxAttempts);
    }

    @Test
    void newJobIsPendingWithDefaults() {
        Job job = new Job("noop", null, NOW, 3);

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals("{}", job.getPayload(), "blank payload normalizes to {}");
        assertEquals(0, job.getAttempts());
        assertNull(job.getWorkerId());
        assertNull(job.getLeaseExpiresAt());
        assertFalse(job.isTerminal());
    }

    @Test
    void constructorRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> new Job("  ", "{}", NOW, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new Job("noop", "{}", null, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new Job("noop", "{}", NOW, 0));
    }

    @Test
    void startAttemptClaimsThePendingJob() {
        Job job = newJob(3);

        job.startAttempt("worker-1", LEASE);

        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertEquals("worker-1", job.getWorkerId());
        assertEquals(LEASE, job.getLeaseExpiresAt());
    }

    @Test
    void startAttemptRejectsNonPendingStates() {
        Job job = newJob(3);
        job.startAttempt("worker-1", LEASE);

        assertThrows(IllegalStateException.class,
                () -> job.startAttempt("worker-2", LEASE),
                "a RUNNING job cannot be claimed again");

        job.succeed("worker-1");

        assertThrows(IllegalStateException.class,
                () -> job.startAttempt("worker-2", LEASE),
                "a terminal job cannot be claimed");
    }

    @Test
    void succeedCompletesTheClaimAndClearsIt() {
        Job job = newJob(3);
        job.startAttempt("worker-1", LEASE);

        assertTrue(job.succeed("worker-1"));

        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
        assertNull(job.getWorkerId());
        assertNull(job.getLeaseExpiresAt());
        assertTrue(job.isTerminal());
    }

    @Test
    void succeedByANonOwnerIsRejectedWithoutStateChange() {
        Job job = newJob(3);
        job.startAttempt("worker-1", LEASE);

        assertFalse(job.succeed("worker-2"));

        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertEquals("worker-1", job.getWorkerId());
    }

    @Test
    void failWithAttemptsRemainingRequeuesWithTheGivenSchedule() {
        Job job = newJob(3);
        job.startAttempt("worker-1", LEASE);

        assertTrue(job.fail("worker-1", "boom", RETRY_AT));

        assertEquals(JobStatus.PENDING, job.getStatus());
        assertEquals(RETRY_AT, job.getScheduledAt());
        assertEquals("boom", job.getLastError());
        assertEquals(1, job.getAttempts(), "attempts already made are kept");
        assertNull(job.getWorkerId());
        assertNull(job.getLeaseExpiresAt());
    }

    @Test
    void failOnTheLastAttemptIsTerminal() {
        Job job = newJob(1);
        job.startAttempt("worker-1", LEASE);

        assertTrue(job.fail("worker-1", "boom", RETRY_AT));

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("boom", job.getLastError());
        assertTrue(job.isTerminal());
    }

    @Test
    void failByANonOwnerIsRejectedWithoutStateChange() {
        Job job = newJob(3);
        job.startAttempt("worker-1", LEASE);

        assertFalse(job.fail("worker-2", "boom", RETRY_AT));

        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertNull(job.getLastError());
    }

    @Test
    void expireLeaseRetriesOrFailsLikeAFailure() {
        Job retryable = newJob(3);
        retryable.startAttempt("worker-1", LEASE);
        retryable.expireLease("lease expired", RETRY_AT);
        assertEquals(JobStatus.PENDING, retryable.getStatus());
        assertEquals(RETRY_AT, retryable.getScheduledAt());

        Job exhausted = newJob(1);
        exhausted.startAttempt("worker-1", LEASE);
        exhausted.expireLease("lease expired", RETRY_AT);
        assertEquals(JobStatus.FAILED, exhausted.getStatus());

        Job pending = newJob(3);
        assertThrows(IllegalStateException.class,
                () -> pending.expireLease("lease expired", RETRY_AT),
                "only a RUNNING job carries a lease");
    }

    @Test
    void cancelIsOnlyValidWhilePending() {
        Job job = newJob(3);
        job.cancel();
        assertEquals(JobStatus.CANCELLED, job.getStatus());
        assertTrue(job.isTerminal());

        assertThrows(JobNotCancellableException.class, job::cancel,
                "a terminal job cannot be cancelled again");

        Job running = newJob(3);
        running.startAttempt("worker-1", LEASE);
        assertThrows(JobNotCancellableException.class, running::cancel,
                "a RUNNING job cannot be cancelled in this milestone");
    }

    @Test
    void extendLeaseOnlyMovesTheOwnersDeadline() {
        Job job = newJob(3);
        job.startAttempt("worker-1", LEASE);

        Instant later = LEASE.plus(30, ChronoUnit.SECONDS);

        job.extendLease("worker-2", later);
        assertEquals(LEASE, job.getLeaseExpiresAt(), "non-owner is a no-op");

        job.extendLease("worker-1", later);
        assertEquals(later, job.getLeaseExpiresAt());
    }
}
