package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.Schedule;
import com.ahmetkeles.jobscheduler.service.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ScheduleApiIntegrationTest extends PostgreSQLIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.AfterEach
    void quiesceSchedules() {
        jdbcTemplate.update("UPDATE schedules SET enabled = false");
    }

    @Test
    void createsASchedule() throws Exception {
        mockMvc.perform(post("/api/schedules")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "nightly-noop", "type": "noop",
                                 "cron": "0 0 3 * * *", "priority": 5,
                                 "maxAttempts": 2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        containsString("/api/schedules/")))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cron").value("0 0 3 * * *"))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.nextRunAt", notNullValue()));
    }

    @Test
    void rejectsInvalidSubmissions() throws Exception {
        mockMvc.perform(post("/api/schedules")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "bad", "type": "noop",
                                 "cron": "not a cron"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/schedules")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\": \"x\", \"cron\": \"0 0 3 * * *\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/schedules")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"name": "x", "type": "noop",
                                 "cron": "0 0 3 * * *", "priority": 1000}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getsAScheduleWithItsRecentJobs() throws Exception {
        Schedule schedule = scheduleService.createSchedule(
                "get-me", "noop", "{}", "0 0 3 * * *", 0, 1);

        jdbcTemplate.update(
                "UPDATE schedules SET next_run_at = now() - interval '1 minute' "
                        + "WHERE id = ?", schedule.getId());
        scheduleService.dispatchDueSchedules(50);

        mockMvc.perform(get("/api/schedules/" + schedule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("get-me"))
                .andExpect(jsonPath("$.recentJobs", hasSize(1)))
                .andExpect(jsonPath("$.recentJobs[0].scheduleId")
                        .value(schedule.getId().toString()))
                .andExpect(jsonPath("$.recentJobs[0].scheduledFor",
                        notNullValue()));
    }

    @Test
    void unknownScheduleIs404() throws Exception {
        mockMvc.perform(get("/api/schedules/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/pause"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pausesAndResumesASchedule() throws Exception {
        Schedule schedule = scheduleService.createSchedule(
                "toggle-me", "noop", "{}", "0 0 3 * * *", 0, 1);

        mockMvc.perform(post("/api/schedules/" + schedule.getId() + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(post("/api/schedules/" + schedule.getId() + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt", notNullValue()));
    }

    @Test
    void listsSchedules() throws Exception {
        scheduleService.createSchedule(
                "list-me", "noop", "{}", "0 0 3 * * *", 0, 1);

        mockMvc.perform(get("/api/schedules").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name",
                        org.hamcrest.Matchers.hasItem("list-me")));

        mockMvc.perform(get("/api/schedules").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }
}
