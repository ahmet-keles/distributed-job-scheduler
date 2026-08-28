package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobStatus;
import com.ahmetkeles.jobscheduler.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final int MAX_LIST_LIMIT = 200;

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(
            @Valid @RequestBody CreateJobRequest request
    ) {
        if (request.hasConflictingSchedule()) {
            throw new IllegalArgumentException(
                    "delaySeconds and scheduledAt are mutually exclusive");
        }

        Job job = jobService.createJob(
                request.type(),
                request.payload(),
                request.delaySeconds(),
                request.scheduledAt(),
                request.maxAttempts()
        );

        return ResponseEntity
                .created(URI.create("/api/jobs/" + job.getId()))
                .body(JobResponse.summary(job));
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable UUID id) {
        Job job = jobService.getJob(id);

        return JobResponse.from(job, jobService.getAttempts(id));
    }

    @GetMapping
    public List<JobResponse> listJobs(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIST_LIMIT);
        }

        return jobService.listJobs(status, limit).stream()
                .map(JobResponse::summary)
                .toList();
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancelJob(@PathVariable UUID id) {
        Job job = jobService.cancelJob(id);

        return JobResponse.summary(job);
    }
}
