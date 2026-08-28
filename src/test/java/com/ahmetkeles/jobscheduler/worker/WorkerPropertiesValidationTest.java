package com.ahmetkeles.jobscheduler.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dangerous worker configuration must refuse to start the application. The
 * heartbeat/lease relation is the critical one: with the heartbeat interval
 * at or beyond the lease duration, every lease expires before its first
 * renewal and each running job is reaped mid-execution as a matter of course.
 */
class WorkerPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(WorkerConfig.class);

    @Configuration
    @EnableConfigurationProperties(WorkerProperties.class)
    static class WorkerConfig {
    }

    @Test
    void defaultsAreValidAndBind() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(WorkerProperties.class));
        });
    }

    @Test
    void heartbeatIntervalEqualToTheLeaseFailsStartup() {
        assertRejected(
                "app.worker.lease-duration=10s",
                "app.worker.heartbeat-interval-ms=10000");
    }

    @Test
    void heartbeatIntervalBeyondTheLeaseFailsStartup() {
        assertRejected(
                "app.worker.lease-duration=5s",
                "app.worker.heartbeat-interval-ms=30000");
    }

    @Test
    void heartbeatIntervalInsideTheLeaseIsAccepted() {
        contextRunner
                .withPropertyValues(
                        "app.worker.lease-duration=30s",
                        "app.worker.heartbeat-interval-ms=10000")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void nonPositiveBoundsFailStartup() {
        assertRejected("app.worker.batch-size=0");
        assertRejected("app.worker.concurrency=-1");
        assertRejected("app.worker.poll-interval-ms=0");
        assertRejected("app.worker.lease-duration=0s");
        assertRejected("app.worker.retry-initial-backoff=-5s");
    }

    private void assertRejected(String... properties) {
        contextRunner
                .withPropertyValues(properties)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();

                    assertNotNull(failure, "startup must fail for "
                            + String.join(", ", properties));
                    assertTrue(hasCause(failure, BindValidationException.class),
                            "must fail property validation, but failed with: "
                                    + failure);
                });
    }

    private static boolean hasCause(
            Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable cause = throwable; cause != null;
                cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
