package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.PostgreSQLIntegrationTest;
import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.service.JobService;
import com.ahmetkeles.jobscheduler.worker.ClaimedJob;
import com.ahmetkeles.jobscheduler.worker.JobClaimService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobProgressWebSocketIntegrationTest extends PostgreSQLIntegrationTest {

    @DynamicPropertySource
    static void progressProperties(DynamicPropertyRegistry registry) {
        registry.add("app.progress.broadcast-interval-ms", () -> "3600000");
    }

    @Autowired
    private JobProgressWebSocketHandler progressHandler;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobClaimService claimService;

    @Test
    void subscriberReceivesInitialAndPersistedStateChanges() throws Exception {
        Job job = jobService.createJob("noop", "{}", null, null, 1);
        WebSocketSession session = sessionFor(job.getId());

        progressHandler.afterConnectionEstablished(session);
        verify(session).sendMessage(any(TextMessage.class));

        ClaimedJob claim = claimService.claimDueJobs("websocket-test-worker", 100)
                .stream()
                .filter(candidate -> candidate.jobId().equals(job.getId()))
                .findFirst().orElseThrow();
        progressHandler.broadcastChanges();
        verify(session, times(2)).sendMessage(any(TextMessage.class));

        claimService.recordSuccess("websocket-test-worker", claim);
        progressHandler.broadcastChanges();
        var messages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(3)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().get(0).getPayload()
                .contains("\"status\":\"PENDING\""));
        assertTrue(messages.getAllValues().get(1).getPayload()
                .contains("\"status\":\"RUNNING\""));
        assertTrue(messages.getAllValues().get(2).getPayload()
                .contains("\"status\":\"SUCCEEDED\""));

        progressHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
    }

    @Test
    void unknownJobSubscriptionIsRejected() throws Exception {
        WebSocketSession session = sessionFor(UUID.randomUUID());

        progressHandler.afterConnectionEstablished(session);

        verify(session).close(any(CloseStatus.class));
    }

    private static WebSocketSession sessionFor(UUID jobId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-" + jobId);
        when(session.getUri()).thenReturn(URI.create(
                "ws://localhost/ws/jobs/" + jobId));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
