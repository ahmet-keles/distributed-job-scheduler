package com.ahmetkeles.jobscheduler.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Job submission. Scheduling is one of three shapes:
 * neither field (run now), {@code delaySeconds} (run that many seconds from
 * now), or {@code scheduledAt} (run at that instant). Supplying both is
 * rejected — two sources of truth for one moment invite silent surprises.
 */
public record CreateJobRequest(
        @NotBlank
        @Size(max = 100)
        String type,

        @Size(max = 10_000)
        String payload,

        @PositiveOrZero
        Long delaySeconds,

        Instant scheduledAt,

        @Min(1)
        @Max(20)
        Integer maxAttempts
) {

    public boolean hasConflictingSchedule() {
        return delaySeconds != null && scheduledAt != null;
    }
}
