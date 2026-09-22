package com.ahmetkeles.jobscheduler.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Recurring schedule submission. {@code cron} is a Spring 6-field expression
 * (second minute hour day month weekday) evaluated in UTC; a malformed
 * expression is rejected with 400 before anything is persisted.
 */
public record CreateScheduleRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 100)
        String type,

        @Size(max = 10_000)
        String payload,

        @NotBlank
        @Size(max = 120)
        String cron,

        @Min(-100)
        @Max(100)
        Integer priority,

        @Min(1)
        @Max(20)
        Integer maxAttempts
) {
}
