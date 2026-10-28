package com.ahmetkeles.jobscheduler.domain;

import java.util.UUID;

/**
 * A pending job can be cancelled immediately and a running job can receive a
 * cooperative cancellation request. Terminal jobs cannot be cancelled.
 */
public class JobNotCancellableException extends RuntimeException {

    public JobNotCancellableException(UUID jobId, JobStatus status) {
        super("Job " + jobId + " cannot be cancelled in status " + status);
    }
}
