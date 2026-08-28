package com.ahmetkeles.jobscheduler.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Stable-for-the-process worker identity: hostname plus a random suffix, so
 * two workers on the same host (or a restarted process whose old leases are
 * still live) never share an id. Overridable via {@code app.worker.id} when a
 * deployment wants deterministic names.
 */
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity(@Value("${app.worker.id:}") String configuredId) {
        if (configuredId != null && !configuredId.isBlank()) {
            this.id = configuredId;
            return;
        }

        this.id = hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            return "worker";
        }
    }

    public String id() {
        return id;
    }
}
