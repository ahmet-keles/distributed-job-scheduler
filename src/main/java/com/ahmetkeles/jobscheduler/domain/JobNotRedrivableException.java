package com.ahmetkeles.jobscheduler.domain;

import java.util.UUID;

/** Raised when a job is not currently waiting in the dead-letter queue. */
public class JobNotRedrivableException extends RuntimeException {

    public JobNotRedrivableException(UUID jobId, JobStatus status) {
        super("Job " + jobId + " cannot be redriven in status " + status);
    }
}
