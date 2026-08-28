package com.ahmetkeles.jobscheduler.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * Lease-owner identity, unique per process <em>incarnation</em>: a
 * human-readable prefix ({@code app.worker.id} if configured, else the
 * hostname) plus a random suffix generated at construction. The suffix is
 * not cosmetic — leases are owned by identity string, so a restarted process
 * reusing a configured id verbatim could heartbeat and complete jobs still
 * leased to its previous incarnation. With the suffix, the old incarnation's
 * leases simply expire and the reaper reclaims them, which is the crash
 * model working as designed.
 */
@Component
public class WorkerIdentity {

    private final String id;

    public WorkerIdentity(@Value("${app.worker.id:}") String configuredPrefix) {
        String prefix =
                configuredPrefix == null || configuredPrefix.isBlank()
                        ? hostname()
                        : configuredPrefix;

        this.id = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
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
