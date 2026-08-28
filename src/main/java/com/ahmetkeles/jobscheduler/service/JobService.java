package com.ahmetkeles.jobscheduler.service;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobAttempt;
import com.ahmetkeles.jobscheduler.domain.JobNotFoundException;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.repository.JobAttemptRepository;
import com.ahmetkeles.jobscheduler.repository.JobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API-facing job operations. Worker-facing transitions live in
 * {@code JobClaimService}; the two meet only at the database row locks.
 */
@Service
public class JobService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;

    public JobService(
            JobRepository jobRepository,
            JobAttemptRepository jobAttemptRepository
    ) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
    }

    /**
     * Creates a PENDING job. {@code delaySeconds} and {@code scheduledAt} are
     * alternative ways to defer it; both null means eligible immediately. A
     * {@code scheduledAt} in the past is accepted and simply means "due now" —
     * rejecting it would make clients race the clock.
     */
    @Transactional
    public Job createJob(
            String type,
            String payload,
            Long delaySeconds,
            Instant scheduledAt,
            Integer maxAttempts
    ) {
        Instant runAt;
        if (scheduledAt != null) {
            runAt = scheduledAt;
        } else if (delaySeconds != null) {
            runAt = Instant.now().plus(Duration.ofSeconds(delaySeconds));
        } else {
            runAt = Instant.now();
        }

        Job job = new Job(
                type,
                payload,
                runAt,
                maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts
        );

        return jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public Job getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    @Transactional(readOnly = true)
    public List<JobAttempt> getAttempts(UUID jobId) {
        return jobAttemptRepository.findByJobIdOrderByAttemptNumberAsc(jobId);
    }

    @Transactional(readOnly = true)
    public List<Job> listJobs(JobStatus status, int limit) {
        PageRequest page = PageRequest.of(0, limit);

        return status == null
                ? jobRepository.findAllByOrderByCreatedAtDesc(page)
                : jobRepository.findByStatusOrderByCreatedAtDesc(status, page);
    }

    /**
     * Cancels a PENDING job. The row is locked first, so this serializes with
     * the workers' claim: whichever transaction wins, the loser sees the
     * committed status — a claimed job is reported not-cancellable rather
     * than yanked out from under its worker, and a cancelled job is skipped
     * by every future claim.
     */
    @Transactional
    public Job cancelJob(UUID jobId) {
        Job job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        job.cancel();

        return job;
    }
}
