package com.ahmetkeles.jobscheduler.worker.handler;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sleeps for {@code {"millis": n}} (default 1000, capped at 60s) — a stand-in
 * for slow work, useful for observing leases and heartbeats in action.
 */
@Component
public class SleepJobHandler implements JobHandler {

    private static final long MAX_SLEEP_MILLIS = 60_000;

    private final ObjectMapper objectMapper;

    public SleepJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "sleep";
    }

    @Override
    public void execute(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        long millis = node.has("millis") ? node.get("millis").asLong() : 1000;

        Thread.sleep(Math.max(0, Math.min(millis, MAX_SLEEP_MILLIS)));
    }
}
