package com.ahmetkeles.jobscheduler.worker;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Worker and retry policy. Every bound is validated at startup and a
 * non-positive value refuses to boot: a zero lease would make every claim
 * instantly reapable, and a zero backoff would hot-loop failing jobs.
 *
 * <p>The safety relation the deployment must preserve:
 * {@code heartbeat-interval < lease-duration}. The margin between the two is
 * how many missed heartbeats a live worker survives before the reaper
 * reclaims — and possibly re-executes — its job.
 */
@Validated
@ConfigurationProperties(prefix = "app.worker")
public class WorkerProperties {

    /** How often the poller looks for claimable jobs. */
    @Positive
    private long pollIntervalMs = 1000;

    /** Jobs claimed per poll. */
    @Positive
    private int batchSize = 5;

    /** Handler threads; the poller claims no more than it can run. */
    @Positive
    private int concurrency = 4;

    /** How long one claim stays valid without a heartbeat. */
    @NotNull
    @DurationMin(millis = 1)
    private Duration leaseDuration = Duration.ofSeconds(30);

    /** How often a worker re-extends the leases of its RUNNING jobs. */
    @Positive
    private long heartbeatIntervalMs = 10_000;

    /** How often expired leases are swept back to PENDING. */
    @Positive
    private long reaperIntervalMs = 15_000;

    /** Expired leases processed per reaper sweep. */
    @Positive
    private int reaperBatchSize = 50;

    /** Delay before the first retry of a failed attempt. */
    @NotNull
    @DurationMin(millis = 1)
    private Duration retryInitialBackoff = Duration.ofSeconds(5);

    /** Factor applied to the backoff after each further failure. */
    @Positive
    private double retryMultiplier = 2.0;

    /** Upper bound for the retry backoff. */
    @NotNull
    @DurationMin(millis = 1)
    private Duration retryMaxBackoff = Duration.ofMinutes(5);

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public long getReaperIntervalMs() {
        return reaperIntervalMs;
    }

    public void setReaperIntervalMs(long reaperIntervalMs) {
        this.reaperIntervalMs = reaperIntervalMs;
    }

    public int getReaperBatchSize() {
        return reaperBatchSize;
    }

    public void setReaperBatchSize(int reaperBatchSize) {
        this.reaperBatchSize = reaperBatchSize;
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = retryInitialBackoff;
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
    }

    public void setRetryMultiplier(double retryMultiplier) {
        this.retryMultiplier = retryMultiplier;
    }

    public Duration getRetryMaxBackoff() {
        return retryMaxBackoff;
    }

    public void setRetryMaxBackoff(Duration retryMaxBackoff) {
        this.retryMaxBackoff = retryMaxBackoff;
    }
}
