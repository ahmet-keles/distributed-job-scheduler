package com.ahmetkeles.jobscheduler.domain;

import java.util.UUID;

/**
 * Only a PENDING job can be cancelled in this milestone: a RUNNING job is
 * already executing on a worker, and a terminal job has nothing to cancel.
 */
public class JobNotCancellableException extends RuntimeException {

    public JobNotCancellableException(UUID jobId, JobStatus status) {
        super("Job " + jobId + " cannot be cancelled in status " + status);
    }
}
