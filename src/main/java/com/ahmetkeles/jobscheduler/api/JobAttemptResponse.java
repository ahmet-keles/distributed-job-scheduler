package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.AttemptOutcome;
import com.ahmetkeles.jobscheduler.domain.JobAttempt;

import java.time.Instant;

public record JobAttemptResponse(
        int attemptNumber,
        String workerId,
        Instant startedAt,
        Instant finishedAt,
        AttemptOutcome outcome,
        String error
) {

    static JobAttemptResponse from(JobAttempt attempt) {
        return new JobAttemptResponse(
                attempt.getAttemptNumber(),
                attempt.getWorkerId(),
                attempt.getStartedAt(),
                attempt.getFinishedAt(),
                attempt.getOutcome(),
                attempt.getError()
        );
    }
}
