package com.ahmetkeles.jobscheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A recurring job definition. A schedule never executes anything itself: each
 * due occurrence is materialized as an ordinary {@link Job}, which then flows
 * through the unchanged claim/lease/retry/history machinery. The schedule
 * row only tracks <em>what to spawn</em> and <em>when next</em>.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@code cron} is a Spring 6-field expression
 *       (second minute hour day month weekday), evaluated in <b>UTC</b>.</li>
 *   <li><b>Misfires collapse:</b> when firing, {@code next_run_at} advances
 *       to the first occurrence strictly after <em>now</em> — occurrences
 *       missed while no dispatcher ran produce one catch-up firing, not a
 *       burst.</li>
 *   <li><b>Resume does not catch up:</b> resuming a paused schedule
 *       recomputes {@code next_run_at} from now; the paused period fires
 *       nothing.</li>
 *   <li><b>Overlap is allowed:</b> firings are independent jobs; a slow
 *       execution does not delay the next occurrence. Handlers must already
 *       be idempotent under the at-least-once model.</li>
 * </ul>
 */
@Entity
@Table(name = "schedules")
public class Schedule {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 120)
    private String cron;

    @Column(nullable = false)
    private int priority;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Schedule() {
    }

    public Schedule(
            String name,
            String jobType,
            String payload,
            String cron,
            int priority,
            int maxAttempts,
            Instant now
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        if (jobType == null || jobType.isBlank()) {
            throw new IllegalArgumentException("jobType is required");
        }

        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }

        this.id = UUID.randomUUID();
        this.name = name;
        this.jobType = jobType;
        this.payload = payload == null || payload.isBlank() ? "{}" : payload;
        this.cron = validatedCron(cron);
        this.priority = priority;
        this.maxAttempts = maxAttempts;
        this.enabled = true;
        this.nextRunAt = nextOccurrenceAfter(now);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Parses and validates; a malformed expression throws immediately. */
    private static String validatedCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron is required");
        }

        CronExpression.parse(cron);
        return cron;
    }

    /** The first cron occurrence strictly after {@code reference}, in UTC. */
    public Instant nextOccurrenceAfter(Instant reference) {
        ZonedDateTime next = CronExpression.parse(cron)
                .next(reference.atZone(ZoneOffset.UTC));

        if (next == null) {
            throw new IllegalArgumentException(
                    "cron expression '" + cron + "' has no future occurrence");
        }

        return next.toInstant();
    }

    /**
     * Records one firing: advances to the first occurrence strictly after
     * {@code now}. Advancing from now — not from the fired occurrence — is
     * the misfire-collapse policy: a backlog of missed occurrences becomes
     * exactly one firing.
     */
    public void advanceAfterFiring(Instant now) {
        nextRunAt = nextOccurrenceAfter(now);
        updatedAt = Instant.now();
    }

    /** Pauses future firings; already-materialized jobs are unaffected. */
    public void pause() {
        if (!enabled) {
            return;
        }

        enabled = false;
        updatedAt = Instant.now();
    }

    /**
     * Resumes firing from the next occurrence after {@code now}; the paused
     * period is skipped, never caught up.
     */
    public void resume(Instant now) {
        if (enabled) {
            return;
        }

        enabled = true;
        nextRunAt = nextOccurrenceAfter(now);
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getJobType() {
        return jobType;
    }

    public String getPayload() {
        return payload;
    }

    public String getCron() {
        return cron;
    }

    public int getPriority() {
        return priority;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
