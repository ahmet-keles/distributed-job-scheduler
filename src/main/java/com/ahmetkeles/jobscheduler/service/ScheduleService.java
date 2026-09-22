package com.ahmetkeles.jobscheduler.service;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.Schedule;
import com.ahmetkeles.jobscheduler.domain.ScheduleNotFoundException;
import com.ahmetkeles.jobscheduler.repository.JobRepository;
import com.ahmetkeles.jobscheduler.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recurring schedules: definition management plus the dispatch transaction
 * that turns due occurrences into ordinary jobs. Execution itself never
 * happens here — a materialized job is claimed, leased, retried, and
 * recorded by the existing worker machinery, each firing independently.
 */
@Service
public class ScheduleService {

    private static final Logger log =
            LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleRepository scheduleRepository;
    private final JobRepository jobRepository;

    public ScheduleService(
            ScheduleRepository scheduleRepository,
            JobRepository jobRepository
    ) {
        this.scheduleRepository = scheduleRepository;
        this.jobRepository = jobRepository;
    }

    /**
     * Creates an enabled schedule whose first firing is the next cron
     * occurrence after now. A malformed cron expression is rejected here,
     * before anything is persisted.
     */
    @Transactional
    public Schedule createSchedule(
            String name,
            String jobType,
            String payload,
            String cron,
            Integer priority,
            Integer maxAttempts
    ) {
        Schedule schedule = new Schedule(
                name,
                jobType,
                payload,
                cron,
                priority == null ? 0 : priority,
                maxAttempts == null ? 3 : maxAttempts,
                Instant.now()
        );

        return scheduleRepository.save(schedule);
    }

    @Transactional(readOnly = true)
    public Schedule getSchedule(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ScheduleNotFoundException(scheduleId));
    }

    @Transactional(readOnly = true)
    public List<Schedule> listSchedules(int limit) {
        return scheduleRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    /** The schedule's most recently materialized jobs, newest first. */
    @Transactional(readOnly = true)
    public List<Job> recentJobs(UUID scheduleId, int limit) {
        getSchedule(scheduleId); // 404 before an empty list for unknown ids

        return jobRepository.findByScheduleIdOrderByCreatedAtDesc(
                scheduleId, PageRequest.of(0, limit));
    }

    /** Stops future firings; already-materialized jobs are unaffected. */
    @Transactional
    public Schedule pauseSchedule(UUID scheduleId) {
        Schedule schedule = getSchedule(scheduleId);
        schedule.pause();
        return schedule;
    }

    /** Resumes from the next occurrence after now; no catch-up firings. */
    @Transactional
    public Schedule resumeSchedule(UUID scheduleId) {
        Schedule schedule = getSchedule(scheduleId);
        schedule.resume(Instant.now());
        return schedule;
    }

    /**
     * One dispatch sweep: locks due schedules with {@code SKIP LOCKED} and,
     * for each, inserts exactly one job for the due occurrence and advances
     * {@code next_run_at} past now — insert and advance commit atomically,
     * so a crash mid-dispatch either fires the occurrence or leaves it due,
     * never both. The unique (schedule_id, scheduled_for) index is the
     * schema-level backstop should that ever regress.
     *
     * @return how many occurrences were materialized
     */
    @Transactional
    public int dispatchDueSchedules(int batchSize) {
        Instant now = Instant.now();

        List<Schedule> due = scheduleRepository.lockDueSchedules(now, batchSize);

        for (Schedule schedule : due) {
            Instant occurrence = schedule.getNextRunAt();

            jobRepository.save(Job.forSchedule(
                    schedule.getJobType(),
                    schedule.getPayload(),
                    occurrence,
                    schedule.getMaxAttempts(),
                    schedule.getPriority(),
                    schedule.getId(),
                    occurrence
            ));

            schedule.advanceAfterFiring(now);

            log.info(
                    "Schedule {} ({}) fired occurrence {}; next run {}",
                    schedule.getId(),
                    schedule.getName(),
                    occurrence,
                    schedule.getNextRunAt()
            );
        }

        return due.size();
    }
}
