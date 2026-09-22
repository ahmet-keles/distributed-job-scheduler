package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.Schedule;
import com.ahmetkeles.jobscheduler.service.ScheduleService;
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
@RequestMapping("/api/schedules")
public class ScheduleController {

    private static final int MAX_LIST_LIMIT = 200;
    private static final int RECENT_JOBS_LIMIT = 20;

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> createSchedule(
            @Valid @RequestBody CreateScheduleRequest request
    ) {
        Schedule schedule = scheduleService.createSchedule(
                request.name(),
                request.type(),
                request.payload(),
                request.cron(),
                request.priority(),
                request.maxAttempts()
        );

        return ResponseEntity
                .created(URI.create("/api/schedules/" + schedule.getId()))
                .body(ScheduleResponse.summary(schedule));
    }

    @GetMapping("/{id}")
    public ScheduleResponse getSchedule(@PathVariable UUID id) {
        Schedule schedule = scheduleService.getSchedule(id);

        return ScheduleResponse.from(
                schedule,
                scheduleService.recentJobs(id, RECENT_JOBS_LIMIT));
    }

    @GetMapping
    public List<ScheduleResponse> listSchedules(
            @RequestParam(defaultValue = "50") int limit
    ) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIST_LIMIT);
        }

        return scheduleService.listSchedules(limit).stream()
                .map(ScheduleResponse::summary)
                .toList();
    }

    @PostMapping("/{id}/pause")
    public ScheduleResponse pauseSchedule(@PathVariable UUID id) {
        return ScheduleResponse.summary(scheduleService.pauseSchedule(id));
    }

    @PostMapping("/{id}/resume")
    public ScheduleResponse resumeSchedule(@PathVariable UUID id) {
        return ScheduleResponse.summary(scheduleService.resumeSchedule(id));
    }
}
