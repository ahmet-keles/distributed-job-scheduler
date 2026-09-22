package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.domain.Schedule;
import com.ahmetkeles.jobscheduler.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recurring-schedule semantics against real PostgreSQL: each due occurrence
 * materializes exactly one ordinary job (even under concurrent dispatchers),
 * misfires collapse, pause stops firing, and resume never catches up.
 */
class ScheduleDispatchIntegrationTest extends PostgreSQLIntegrationTest {

    /** Fires at second 0 of every hour — never due during a test by luck. */
    private static final String HOURLY = "0 0 * * * *";

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JobClaimService claimService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The database is shared across test classes: disable every schedule and
     * cancel still-pending materialized jobs so this class's high-priority
     * spawn can never leak into another class's claim-ordering assertions.
     */
    @org.junit.jupiter.api.AfterEach
    void quiesceSchedules() {
        jdbcTemplate.update("UPDATE schedules SET enabled = false");
        jdbcTemplate.update(
                "UPDATE jobs SET status = 'CANCELLED' "
                        + "WHERE status = 'PENDING' AND schedule_id IS NOT NULL");
    }

    private Schedule newHourlySchedule(String name) {
        return scheduleService.createSchedule(
                name, "noop", "{\"from\":\"schedule\"}", HOURLY, 7, 2);
    }

    /**
     * Test seam: makes the schedule's next occurrence already due. The
     * instant is truncated to microseconds up front — PostgreSQL stores
     * TIMESTAMP(6) — so equality assertions survive the round-trip.
     */
    private Instant makeDue(UUID scheduleId, Instant occurrence) {
        Instant stored = occurrence
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbcTemplate.update(
                "UPDATE schedules SET next_run_at = ? WHERE id = ?",
                stored.atOffset(java.time.ZoneOffset.UTC), scheduleId);
        return stored;
    }

    @Test
    void creationComputesTheNextUtcOccurrence() {
        Instant before = Instant.now();
        Schedule schedule = newHourlySchedule("create-semantics");

        assertTrue(schedule.isEnabled());
        assertTrue(schedule.getNextRunAt().isAfter(before),
                "the first firing is the next occurrence after creation");
        assertEquals(0, schedule.getNextRunAt()
                        .atZone(java.time.ZoneOffset.UTC).getMinute(),
                "an hourly cron must land on minute 0, evaluated in UTC");
    }

    @Test
    void invalidCronIsRejectedBeforeAnythingIsPersisted() {
        assertThrows(IllegalArgumentException.class, () ->
                scheduleService.createSchedule(
                        "bad", "noop", "{}", "not a cron", 0, 1));
    }

    @Test
    void aDueOccurrenceMaterializesExactlyOneOrdinaryJob() {
        Schedule schedule = newHourlySchedule("fires-once");
        Instant occurrence = makeDue(
                schedule.getId(), Instant.now().minusSeconds(30));

        int fired = scheduleService.dispatchDueSchedules(50);
        assertTrue(fired >= 1);

        List<Job> jobs = scheduleService.recentJobs(schedule.getId(), 10);
        assertEquals(1, jobs.size(),
                "one due occurrence must materialize exactly one job");

        Job job = jobs.get(0);
        assertEquals("noop", job.getType());
        assertEquals("{\"from\":\"schedule\"}", job.getPayload());
        assertEquals(7, job.getPriority());
        assertEquals(2, job.getMaxAttempts());
        assertEquals(schedule.getId(), job.getScheduleId());
        assertEquals(occurrence, job.getScheduledFor(),
                "the job records which nominal occurrence it represents");
        assertEquals(JobStatus.PENDING, job.getStatus());
        assertTrue(!job.getScheduledAt().isAfter(Instant.now()),
                "the materialized job is due immediately");

        Schedule advanced = scheduleService.getSchedule(schedule.getId());
        assertTrue(advanced.getNextRunAt().isAfter(Instant.now()),
                "the schedule advances strictly past now — misfires collapse "
                        + "into the one firing just materialized");

        // The spawned job is claimed and completed by the ordinary machinery.
        ClaimedJob claim = claimService
                .claimDueJobs("schedule-worker", 100).stream()
                .filter(candidate -> candidate.jobId().equals(job.getId()))
                .findFirst().orElseThrow();
        claimService.recordSuccess("schedule-worker", claim);
        assertEquals(JobStatus.SUCCEEDED,
                claimService.loadJob(job.getId()).getStatus());
    }

    @Test
    void everyFiringIsRecordedAsItsOwnIndependentJob() {
        Schedule schedule = newHourlySchedule("independent-firings");

        makeDue(schedule.getId(), Instant.now().minusSeconds(7200));
        scheduleService.dispatchDueSchedules(50);
        makeDue(schedule.getId(), Instant.now().minusSeconds(3600));
        scheduleService.dispatchDueSchedules(50);

        List<Job> jobs = scheduleService.recentJobs(schedule.getId(), 10);
        assertEquals(2, jobs.size(),
                "two firings must produce two independent job rows");
        assertNotEquals(jobs.get(0).getId(), jobs.get(1).getId());
        assertNotEquals(jobs.get(0).getScheduledFor(),
                jobs.get(1).getScheduledFor(),
                "each job carries its own nominal occurrence");
    }

    @Test
    void aNotYetDueScheduleFiresNothing() {
        Schedule schedule = newHourlySchedule("not-due");

        scheduleService.dispatchDueSchedules(50);

        assertEquals(0, scheduleService
                        .recentJobs(schedule.getId(), 10).size(),
                "a schedule whose next occurrence is in the future must "
                        + "not fire");
    }

    @Test
    void pauseStopsFiringAndResumeSkipsThePausedPeriod() {
        Schedule schedule = newHourlySchedule("pause-resume");
        scheduleService.pauseSchedule(schedule.getId());

        makeDue(schedule.getId(), Instant.now().minusSeconds(30));
        scheduleService.dispatchDueSchedules(50);
        assertEquals(0, scheduleService
                        .recentJobs(schedule.getId(), 10).size(),
                "a paused schedule must never fire, even when due");

        Schedule resumed = scheduleService.resumeSchedule(schedule.getId());
        assertTrue(resumed.isEnabled());
        assertTrue(resumed.getNextRunAt().isAfter(Instant.now()),
                "resume recomputes from now — the paused period is skipped, "
                        + "not caught up");

        scheduleService.dispatchDueSchedules(50);
        assertEquals(0, scheduleService
                        .recentJobs(schedule.getId(), 10).size(),
                "nothing fires until the next occurrence after the resume");
    }

    @Test
    void theSchemaRejectsASecondJobForTheSameOccurrence() {
        Schedule schedule = newHourlySchedule("unique-backstop");
        makeDue(schedule.getId(), Instant.now().minusSeconds(30));
        scheduleService.dispatchDueSchedules(50);

        Job fired = scheduleService.recentJobs(schedule.getId(), 10).get(0);

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("""
                        INSERT INTO jobs (id, type, payload, status,
                            scheduled_at, attempts, max_attempts, priority,
                            schedule_id, scheduled_for, created_at, updated_at)
                        VALUES (?, 'noop', '{}', 'PENDING', now(), 0, 1, 0,
                            ?, ?, now(), now())
                        """,
                        UUID.randomUUID(),
                        schedule.getId(),
                        fired.getScheduledFor()
                                .atOffset(java.time.ZoneOffset.UTC)),
                "the unique (schedule_id, scheduled_for) index must refuse "
                        + "a duplicate materialization of one occurrence");
    }

    @Test
    void concurrentDispatchersFireEachDueScheduleExactlyOnce()
            throws Exception {
        Set<UUID> scheduleIds = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            Schedule schedule = newHourlySchedule("concurrent-" + i);
            makeDue(schedule.getId(), Instant.now().minusSeconds(30));
            scheduleIds.add(schedule.getId());
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = pool.submit(() -> {
                start.await();
                return scheduleService.dispatchDueSchedules(50);
            });
            Future<Integer> second = pool.submit(() -> {
                start.await();
                return scheduleService.dispatchDueSchedules(50);
            });

            start.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);

            for (UUID scheduleId : scheduleIds) {
                assertEquals(1,
                        scheduleService.recentJobs(scheduleId, 10).size(),
                        "each due schedule must fire exactly once across "
                                + "concurrent dispatchers");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
