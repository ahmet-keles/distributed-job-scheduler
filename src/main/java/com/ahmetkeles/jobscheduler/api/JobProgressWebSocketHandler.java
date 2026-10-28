package com.ahmetkeles.jobscheduler.api;

import com.ahmetkeles.jobscheduler.domain.Job;
import com.ahmetkeles.jobscheduler.domain.JobNotFoundException;
import com.ahmetkeles.jobscheduler.service.JobService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Streams job-state snapshots to connected clients. */
@Component
public class JobProgressWebSocketHandler extends TextWebSocketHandler {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, ConcurrentMap<String, WebSocketSession>> sessionsByJob =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, JobProgressEvent> lastSentBySession =
            new ConcurrentHashMap<>();

    public JobProgressWebSocketHandler(JobService jobService, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        UUID jobId = jobId(rawSession.getUri());
        if (jobId == null) {
            rawSession.close(CloseStatus.POLICY_VIOLATION.withReason(
                    "Connect to /ws/jobs/{jobId}"));
            return;
        }

        Job job;
        try {
            job = jobService.getJob(jobId);
        } catch (JobNotFoundException missing) {
            rawSession.close(CloseStatus.POLICY_VIOLATION.withReason("Unknown job"));
            return;
        }

        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        JobProgressEvent initial = JobProgressEvent.from(job);
        send(session, initial);
        lastSentBySession.put(session.getId(), initial);
        sessionsByJob.computeIfAbsent(jobId, ignored -> new ConcurrentHashMap<>())
                .put(session.getId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message)
            throws IOException {
        session.close(CloseStatus.POLICY_VIOLATION.withReason(
                "Progress subscriptions are read-only"));
    }

    /** Poll persisted state so updates made by another scheduler instance are visible. */
    @Scheduled(fixedDelayString = "${app.progress.broadcast-interval-ms:250}")
    public void broadcastChanges() {
        sessionsByJob.forEach((jobId, sessions) -> {
            Job job;
            try {
                job = jobService.getJob(jobId);
            } catch (JobNotFoundException missing) {
                sessions.values().forEach(session -> close(session,
                        CloseStatus.POLICY_VIOLATION.withReason("Job no longer exists")));
                return;
            }

            JobProgressEvent current = JobProgressEvent.from(job);
            sessions.values().forEach(session -> {
                if (!session.isOpen()) {
                    remove(session.getId());
                    return;
                }

                if (!current.equals(lastSentBySession.get(session.getId()))) {
                    try {
                        send(session, current);
                        lastSentBySession.put(session.getId(), current);
                    } catch (IOException exception) {
                        close(session, CloseStatus.SERVER_ERROR);
                    }
                }
            });
        });
    }

    private void send(WebSocketSession session, JobProgressEvent event)
            throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
    }

    private static UUID jobId(URI uri) {
        if (uri == null || uri.getPath() == null) {
            return null;
        }

        String[] parts = uri.getPath().split("/");
        if (parts.length != 4 || !parts[1].equals("ws") || !parts[2].equals("jobs")) {
            return null;
        }
        try {
            return UUID.fromString(parts[3]);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // The socket is already gone; cleanup still removes its subscription.
        } finally {
            remove(session.getId());
        }
    }

    private void remove(String sessionId) {
        lastSentBySession.remove(sessionId);
        sessionsByJob.values().forEach(sessions -> {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                sessionsByJob.entrySet().removeIf(entry -> entry.getValue() == sessions);
            }
        });
    }
}
