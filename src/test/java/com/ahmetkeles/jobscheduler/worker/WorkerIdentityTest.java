package com.ahmetkeles.jobscheduler.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerIdentityTest {

    @Test
    void configuredIdBecomesAPrefixWithAProcessUniqueSuffix() {
        WorkerIdentity identity = new WorkerIdentity("worker-east");

        assertTrue(identity.id().startsWith("worker-east-"));
        assertTrue(identity.id().length() > "worker-east-".length(),
                "the suffix must be non-empty");
    }

    @Test
    void twoIncarnationsWithTheSamePrefixNeverShareAnIdentity() {
        WorkerIdentity first = new WorkerIdentity("worker-east");
        WorkerIdentity second = new WorkerIdentity("worker-east");

        assertNotEquals(first.id(), second.id(),
                "a restarted process reusing the configured id verbatim "
                        + "could heartbeat its predecessor's leases; the "
                        + "random suffix must make that impossible");
    }

    @Test
    void blankConfigurationFallsBackToAHostDerivedPrefix() {
        WorkerIdentity fromNull = new WorkerIdentity(null);
        WorkerIdentity fromBlank = new WorkerIdentity("  ");

        assertTrue(fromNull.id().contains("-"));
        assertNotEquals(fromNull.id(), fromBlank.id());
    }
}
