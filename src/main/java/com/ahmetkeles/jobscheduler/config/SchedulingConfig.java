package com.ahmetkeles.jobscheduler.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled triggers. Worker triggers still require worker beans;
 * progress streaming also runs on API-only nodes.
 * The scheduler pool size is configured in application.properties: poller,
 * heartbeat, and reaper must not serialize behind one another.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
