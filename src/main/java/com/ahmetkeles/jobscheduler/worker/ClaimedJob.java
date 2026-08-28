package com.ahmetkeles.jobscheduler.worker;

import java.util.UUID;

/**
 * Detached snapshot of a claim, handed from the claim transaction to the
 * execution thread. Carries everything the handler and the completion
 * transaction need, so execution never touches a managed entity outside a
 * transaction.
 */
public record ClaimedJob(
        UUID jobId,
        String type,
        String payload,
        int attemptNumber
) {
}
