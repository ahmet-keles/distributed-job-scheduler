package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobAttempt;
import com.ahmetkeles.jobscheduler.domain.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String type,
        String payload,
        JobStatus status,
        Instant scheduledAt,
        int priority,
        UUID scheduleId,
        Instant scheduledFor,
        int attempts,
        int maxAttempts,
        String workerId,
        Instant leaseExpiresAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        List<JobAttemptResponse> attemptHistory
) {

    static JobResponse from(Job job, List<JobAttempt> attempts) {
        return new JobResponse(
                job.getId(),
                job.getType(),
                job.getPayload(),
                job.getStatus(),
                job.getScheduledAt(),
                job.getPriority(),
                job.getScheduleId(),
                job.getScheduledFor(),
                job.getAttempts(),
                job.getMaxAttempts(),
                job.getWorkerId(),
                job.getLeaseExpiresAt(),
                job.getLastError(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                attempts == null
                        ? null
                        : attempts.stream().map(JobAttemptResponse::from).toList()
        );
    }

    static JobResponse summary(Job job) {
        return from(job, null);
    }
}
