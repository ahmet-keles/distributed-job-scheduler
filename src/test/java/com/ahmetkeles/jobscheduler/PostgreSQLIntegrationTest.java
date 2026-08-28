package com.ahmetkeles.jobscheduler;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests: real PostgreSQL via Testcontainers, no
 * background worker — the poller, heartbeat, and reaper are disabled so each
 * test drives {@code JobClaimService} deterministically itself. The full
 * scheduled loop is exercised separately by {@code JobWorkerEndToEndTest}.
 */
@SpringBootTest
public abstract class PostgreSQLIntegrationTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("app.worker.enabled", () -> "false");
    }
}
