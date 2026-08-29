package com.ahmetkeles.jobscheduler.domain;

import java.util.UUID;

public class ScheduleNotFoundException extends RuntimeException {

    public ScheduleNotFoundException(UUID scheduleId) {
        super("Schedule not found: " + scheduleId);
    }
}
