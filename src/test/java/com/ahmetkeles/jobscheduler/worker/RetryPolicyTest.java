package com.ahmetkeles.jobscheduler.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {

    private static RetryPolicy policy(
            Duration initial, double multiplier, Duration max) {
        WorkerProperties properties = new WorkerProperties();
        properties.setRetryInitialBackoff(initial);
        properties.setRetryMultiplier(multiplier);
        properties.setRetryMaxBackoff(max);
        return new RetryPolicy(properties);
    }

    @Test
    void backoffGrowsExponentiallyFromTheInitialDelay() {
        RetryPolicy policy = policy(
                Duration.ofSeconds(5), 2.0, Duration.ofMinutes(5));

        assertEquals(Duration.ofSeconds(5), policy.backoffAfter(1));
        assertEquals(Duration.ofSeconds(10), policy.backoffAfter(2));
        assertEquals(Duration.ofSeconds(20), policy.backoffAfter(3));
        assertEquals(Duration.ofSeconds(40), policy.backoffAfter(4));
    }

    @Test
    void backoffIsCappedAtTheConfiguredMaximum() {
        RetryPolicy policy = policy(
                Duration.ofSeconds(5), 2.0, Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(30), policy.backoffAfter(4),
                "40s uncapped, clamped to 30s");
        assertEquals(Duration.ofSeconds(30), policy.backoffAfter(50),
                "very large attempt counts must not overflow past the cap");
    }

    @Test
    void multiplierOfOneKeepsTheBackoffFixed() {
        RetryPolicy policy = policy(
                Duration.ofSeconds(7), 1.0, Duration.ofMinutes(5));

        assertEquals(Duration.ofSeconds(7), policy.backoffAfter(1));
        assertEquals(Duration.ofSeconds(7), policy.backoffAfter(6));
    }

    @Test
    void rejectsNonPositiveAttemptCounts() {
        RetryPolicy policy = policy(
                Duration.ofSeconds(5), 2.0, Duration.ofMinutes(5));

        assertThrows(IllegalArgumentException.class,
                () -> policy.backoffAfter(0));
    }
}
