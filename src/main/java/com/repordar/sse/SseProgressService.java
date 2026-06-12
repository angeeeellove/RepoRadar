package com.repordar.sse;

import com.repordar.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

/**
 * SSE 进度推送服务，通过 SseEmitter 向浏览器推送管线进度。
 */
@Slf4j
@Service
public class SseProgressService {

    private static final long SSE_TIMEOUT_MS = 300_000L;

    private SseEmitter emitter;

    public SseEmitter createEmitter() {
        this.emitter = new SseEmitter(SSE_TIMEOUT_MS);
        return this.emitter;
    }

    public void sendProgress(String stage, String message, int percent) {
        send("progress", ApiResponse.ok(new ProgressData(stage, message, percent)));
    }

    public void sendComplete(String reportPath) {
        send("complete", ApiResponse.ok(new ReportPathData(reportPath)));
        if (emitter != null) {
            emitter.complete();
        }
    }

    public void sendError(int code, String msg) {
        send("error", ApiResponse.error(code, msg));
        if (emitter != null) {
            emitter.completeWithError(new RuntimeException(msg));
        }
    }

    private void send(String eventName, Object data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("SSE 客户端已断开连接，跳过推送: {}", e.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressData {
        private String stage;
        private String message;
        private int percent;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportPathData {
        private String reportPath;
    }
}
