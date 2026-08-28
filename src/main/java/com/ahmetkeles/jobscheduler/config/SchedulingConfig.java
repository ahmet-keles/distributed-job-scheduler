package com.ahmetkeles.jobscheduler.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the worker's scheduled triggers (poller, heartbeat, reaper). Gated
 * by the same flag as the worker beans so API-only deployments — and tests
 * that drive the claim service directly — run no background schedules at all.
 * The scheduler pool size is configured in application.properties: poller,
 * heartbeat, and reaper must not serialize behind one another.
 */
@Configuration
@ConditionalOnProperty(
        name = "app.worker.enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableScheduling
public class SchedulingConfig {
}
