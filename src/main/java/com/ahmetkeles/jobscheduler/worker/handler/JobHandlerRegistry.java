package com.ahmetkeles.jobscheduler.worker.handler;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Type-to-handler lookup, built once from all {@link JobHandler} beans.
 * Duplicate types fail startup — silently shadowing one handler with another
 * would misroute every job of that type.
 */
@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> discovered) {
        Map<String, JobHandler> byType = new HashMap<>();

        for (JobHandler handler : discovered) {
            JobHandler previous = byType.putIfAbsent(handler.type(), handler);

            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate job handler for type '" + handler.type()
                                + "': " + previous.getClass().getName()
                                + " and " + handler.getClass().getName());
            }
        }

        this.handlers = Map.copyOf(byType);
    }

    public Optional<JobHandler> find(String type) {
        return Optional.ofNullable(handlers.get(type));
    }

    public Set<String> registeredTypes() {
        return handlers.keySet();
    }
}
