package com.ahmetkeles.jobscheduler.worker.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Succeeds immediately; the smoke-test job type. */
@Component
public class NoOpJobHandler implements JobHandler {

    private static final Logger log =
            LoggerFactory.getLogger(NoOpJobHandler.class);

    @Override
    public String type() {
        return "noop";
    }

    @Override
    public void execute(String payload) {
        log.info("noop job executed with payload {}", payload);
    }
}
