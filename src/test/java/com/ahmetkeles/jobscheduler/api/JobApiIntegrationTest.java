package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.service.JobService;
import com.ahmetkeles.jobscheduler.worker.JobClaimService;
import com.ahmetkeles.jobscheduler.worker.ClaimedJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@AutoConfigureMockMvc
class JobApiIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    @Test
    void createsAnImmediateJob() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type": "noop", "payload": "{\\"k\\":1}"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        containsString("/api/jobs/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.maxAttempts").value(3))
                .andExpect(jsonPath("$.scheduledAt", notNullValue()));
    }

    @Test
    void createsADelayedJobScheduledInTheFuture() throws Exception {
        Instant before = Instant.now();

        String body = mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type": "noop", "delaySeconds": 3600}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(body.replaceAll(
                ".*\"id\"\\s*:\\s*\"([0-9a-f-]+)\".*", "$1"));

        Job job = jobService.getJob(id);
        Instant earliest = before.plus(3600, ChronoUnit.SECONDS);

        org.junit.jupiter.api.Assertions.assertFalse(
                job.getScheduledAt().isBefore(earliest),
                "a delayed job must not be schedulable before its delay");
    }

    @Test
    void acceptsAnExplicitScheduledAt() throws Exception {
        Instant at = Instant.now().plus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MILLIS);

        mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type": "noop", "scheduledAt": "%s"}
                                """.formatted(at)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduledAt").value(at.toString()));
    }

    @Test
    void rejectsDelayAndScheduledAtTogether() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type": "noop", "delaySeconds": 5,
                                 "scheduledAt": "2030-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        containsString("mutually exclusive")));
    }

    @Test
    void rejectsInvalidSubmissions() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\": \"  \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("{\"type\": \"noop\", \"maxAttempts\": 0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/jobs")
                        .contentType(APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsAJobWithItsAttemptHistory() throws Exception {
        Job job = jobService.createJob("noop", "{}", null, null, 2);

        List<ClaimedJob> claims = claimService.claimDueJobs("api-test-worker", 10);
        ClaimedJob mine = claims.stream()
                .filter(claim -> claim.jobId().equals(job.getId()))
                .findFirst().orElseThrow();
        claimService.recordFailure("api-test-worker", mine, "first try failed");

        mockMvc.perform(get("/api/jobs/" + job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.lastError").value("first try failed"))
                .andExpect(jsonPath("$.attemptHistory", hasSize(1)))
                .andExpect(jsonPath("$.attemptHistory[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.attemptHistory[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.attemptHistory[0].workerId")
                        .value("api-test-worker"));
    }

    @Test
    void unknownJobIs404() throws Exception {
        mockMvc.perform(get("/api/jobs/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/jobs/" + UUID.randomUUID() + "/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelsAPendingJobExactlyOnce() throws Exception {
        Job job = jobService.createJob(
                "noop", "{}", 3600L, null, null);

        mockMvc.perform(post("/api/jobs/" + job.getId() + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(post("/api/jobs/" + job.getId() + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message",
                        containsString("CANCELLED")));

        assertEquals("CANCELLED",
                jobService.getJob(job.getId()).getStatus().name());
    }

    @Test
    void aRunningJobCannotBeCancelled() throws Exception {
        Job job = jobService.createJob("noop", "{}", null, null, null);

        claimService.claimDueJobs("api-test-worker", 50);

        mockMvc.perform(post("/api/jobs/" + job.getId() + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("RUNNING")));
    }

    @Test
    void listsJobsNewestFirstWithOptionalStatusFilter() throws Exception {
        Job cancelled = jobService.createJob("noop", "{}", 3600L, null, null);
        jobService.cancelJob(cancelled.getId());
        jobService.createJob("noop", "{}", 3600L, null, null);

        mockMvc.perform(get("/api/jobs").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));

        mockMvc.perform(get("/api/jobs")
                        .param("status", "CANCELLED")
                        .param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is("CANCELLED"))));

        mockMvc.perform(get("/api/jobs").param("limit", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/jobs").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }
}
