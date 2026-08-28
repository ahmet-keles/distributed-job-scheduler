package com.ahmetkeles.jobscheduler.worker.handler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobHandlerRegistryTest {

    private record FixedHandler(String type) implements JobHandler {
        @Override
        public void execute(String payload) {
        }
    }

    @Test
    void resolvesHandlersByType() {
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(
                new FixedHandler("a"), new FixedHandler("b")));

        assertEquals(Set.of("a", "b"), registry.registeredTypes());
        assertTrue(registry.find("a").isPresent());
        assertTrue(registry.find("missing").isEmpty(),
                "unknown types resolve to empty, not an exception");
    }

    @Test
    void duplicateTypesFailStartup() {
        List<JobHandler> clash =
                List.of(new FixedHandler("a"), new FixedHandler("a"));

        assertThrows(IllegalStateException.class,
                () -> new JobHandlerRegistry(clash),
                "silently shadowing a handler would misroute every job of "
                        + "that type");
    }
}
