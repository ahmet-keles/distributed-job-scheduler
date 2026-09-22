package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.Schedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScheduleResponse(
        UUID id,
        String name,
        String type,
        String payload,
        String cron,
        int priority,
        int maxAttempts,
        boolean enabled,
        Instant nextRunAt,
        Instant createdAt,
        Instant updatedAt,
        List<JobResponse> recentJobs
) {

    static ScheduleResponse from(Schedule schedule, List<Job> recentJobs) {
        return new ScheduleResponse(
                schedule.getId(),
                schedule.getName(),
                schedule.getJobType(),
                schedule.getPayload(),
                schedule.getCron(),
                schedule.getPriority(),
                schedule.getMaxAttempts(),
                schedule.isEnabled(),
                schedule.getNextRunAt(),
                schedule.getCreatedAt(),
                schedule.getUpdatedAt(),
                recentJobs == null
                        ? null
                        : recentJobs.stream().map(JobResponse::summary).toList()
        );
    }

    static ScheduleResponse summary(Schedule schedule) {
        return from(schedule, null);
    }
}
