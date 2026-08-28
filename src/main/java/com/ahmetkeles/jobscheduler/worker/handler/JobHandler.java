package com.ahmetkeles.jobscheduler.worker.handler;

/**
 * A unit of executable work, selected by job type. Implementations are Spring
 * beans; the registry discovers them at startup.
 *
 * <p>Execution is at-least-once: a lease that expires mid-run means the same
 * payload is executed again by another worker, so handlers must be idempotent
 * or tolerate duplicates. Throwing any exception marks the attempt FAILED and
 * triggers the retry policy.
 */
public interface JobHandler {

    /** The job type this handler executes; must be unique across handlers. */
    String type();

    /** Runs the job. @param payload the job's JSON payload document */
    void execute(String payload) throws Exception;
}
