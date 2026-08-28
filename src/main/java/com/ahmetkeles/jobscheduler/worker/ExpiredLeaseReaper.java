package com.ahmetkeles.jobscheduler.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps RUNNING jobs whose lease deadline has passed back to PENDING (or
 * FAILED once attempts are exhausted). Runs on every node — {@code SKIP
 * LOCKED} in the underlying query partitions expired rows between concurrent
 * sweepers — so crash recovery does not depend on any single process.
 */
@Component
@ConditionalOnProperty(
        name = "app.worker.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ExpiredLeaseReaper {

    private final JobClaimService claimService;

    public ExpiredLeaseReaper(JobClaimService claimService) {
        this.claimService = claimService;
    }

    @Scheduled(fixedDelayString = "${app.worker.reaper-interval-ms:15000}")
    public void sweep() {
        claimService.requeueExpiredLeases();
    }
}
