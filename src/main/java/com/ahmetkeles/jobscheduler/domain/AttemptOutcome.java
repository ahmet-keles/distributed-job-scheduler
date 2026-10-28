package com.ahmetkeles.jobscheduler.domain;

/**
 * Terminal outcome of one execution attempt. ABANDONED marks an attempt whose
 * worker stopped heartbeating: the lease expired and the reaper reclaimed the
 * job without knowing whether the handler ran to completion. CANCELLED marks
 * an attempt stopped at the client's request.
 */
public enum AttemptOutcome {
    SUCCEEDED,
    FAILED,
    ABANDONED,
    CANCELLED
}
