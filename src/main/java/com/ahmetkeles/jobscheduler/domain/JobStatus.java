package com.ahmetkeles.jobscheduler.domain;

/**
 * Lifecycle of a job.
 *
 * <pre>
 * PENDING ──claim──▶ RUNNING ──▶ SUCCEEDED
 *    ▲                 │
 *    │  retry left     ├──▶ FAILED     (attempts exhausted, or terminal error)
 *    └─────────────────┘
 * PENDING ──cancel──▶ CANCELLED
 * </pre>
 *
 * SUCCEEDED, FAILED and CANCELLED are terminal; no transition leaves them.
 */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
