package com.ahmetkeles.jobscheduler.worker;

import com.ahmetkeles.jobscheduler.service.ScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns due schedule occurrences into ordinary jobs on a fixed cadence.
 * Runs on every worker instance — {@code SKIP LOCKED} in the underlying
 * query partitions due schedules between concurrent dispatchers, so adding
 * instances adds dispatch capacity without double-firing.
 */
@Component
@ConditionalOnProperty(
        name = "app.worker.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ScheduleDispatcher {

    private final ScheduleService scheduleService;
    private final WorkerProperties properties;

    public ScheduleDispatcher(
            ScheduleService scheduleService,
            WorkerProperties properties
    ) {
        this.scheduleService = scheduleService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.worker.schedule-dispatch-interval-ms:1000}")
    public void dispatch() {
        scheduleService.dispatchDueSchedules(
                properties.getScheduleDispatchBatchSize());
    }
}
