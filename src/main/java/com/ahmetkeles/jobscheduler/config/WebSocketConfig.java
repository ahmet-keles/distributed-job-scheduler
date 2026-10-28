package com.ahmetkeles.jobscheduler.config;

import com.ahmetkeles.jobscheduler.api.JobProgressWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JobProgressWebSocketHandler progressHandler;

    public WebSocketConfig(JobProgressWebSocketHandler progressHandler) {
        this.progressHandler = progressHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(progressHandler, "/ws/jobs/*");
    }
}
