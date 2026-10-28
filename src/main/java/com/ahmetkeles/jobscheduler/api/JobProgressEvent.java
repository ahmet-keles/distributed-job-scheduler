package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/** Compact persisted-state update sent to a job progress subscriber. */
public record JobProgressEvent(
        String type,
        UUID jobId,
        JobStatus status,
        int attempts,
        int maxAttempts,
        boolean cancelRequested,
        String lastError,
        Instant updatedAt
) {

    public static JobProgressEvent from(Job job) {
        return new JobProgressEvent(
                "job.progress",
                job.getId(),
                job.getStatus(),
                job.getAttempts(),
                job.getMaxAttempts(),
                job.isCancelRequested(),
                job.getLastError(),
                job.getUpdatedAt()
        );
    }
}
