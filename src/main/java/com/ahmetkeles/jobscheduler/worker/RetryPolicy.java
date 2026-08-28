package com.ahmetkeles.jobscheduler.worker;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Exponential backoff for failed attempts: the delay before retry N (of the
 * attempts already made) is {@code initial * multiplier^(N-1)}, capped at the
 * configured maximum. Deliberately deterministic — no jitter — so tests and
 * operators can predict exactly when a job becomes claimable again.
 */
@Component
public class RetryPolicy {

    private final WorkerProperties properties;

    public RetryPolicy(WorkerProperties properties) {
        this.properties = properties;
    }

    /** Delay before the next run, given how many attempts have now failed. */
    public Duration backoffAfter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException(
                    "failedAttempts must be at least 1, was " + failedAttempts);
        }

        double factor = Math.pow(
                properties.getRetryMultiplier(),
                failedAttempts - 1
        );

        double millis =
                properties.getRetryInitialBackoff().toMillis() * factor;

        long capped = (long) Math.min(
                millis,
                properties.getRetryMaxBackoff().toMillis()
        );

        return Duration.ofMillis(capped);
    }
}
