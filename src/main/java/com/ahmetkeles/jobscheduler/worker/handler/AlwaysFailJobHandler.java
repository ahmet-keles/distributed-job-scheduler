package com.ahmetkeles.jobscheduler.worker.handler;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Always throws — the job type for exercising retries, backoff, and the
 * terminal FAILED state end to end. Optional payload
 * {@code {"message": "..."}} customizes the error recorded on each attempt.
 */
@Component
public class AlwaysFailJobHandler implements JobHandler {

    private final ObjectMapper objectMapper;

    public AlwaysFailJobHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "always-fail";
    }

    @Override
    public void execute(String payload) {
        String message = "always-fail job failed as designed";

        JsonNode node = objectMapper.readTree(payload);
        if (node.has("message")) {
            message = node.get("message").asText();
        }

        throw new IllegalStateException(message);
    }
}
