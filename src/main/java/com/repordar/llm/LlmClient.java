package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容 API 客户端，使用 java.net.http.HttpClient。
 *
 * @author frank
 */
@Slf4j
@Component
public class LlmClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final double TEMPERATURE = 0.1;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 发送聊天请求到 OpenAI 兼容 API。
     *
     * @param baseUrl API 基础 URL
     * @param apiKey  API 密钥
     * @param model   模型名称
     * @param messages 消息列表
     * @return LLM 响应内容
     * @throws IOException 如果请求失败
     */
    public String chat(String baseUrl, String apiKey, String model, List<Message> messages) throws IOException {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    new ChatRequest(model, messages, TEMPERATURE));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            log.info("调用 LLM: model={}, url={}", model, maskUrl(baseUrl));

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("LLM API 返回错误: " + response.statusCode() + " " + response.body());
            }

            ChatResponse chatResponse = objectMapper.readValue(response.body(), ChatResponse.class);
            return chatResponse.choices().get(0).message().content();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM 请求被中断", e);
        }
    }

    /**
     * 脱敏 URL，避免在日志中暴露敏感信息。
     */
    private String maskUrl(String url) {
        if (url == null) {
            return "null";
        }
        // 仅保留协议和主机，隐藏路径
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "[masked]";
        }
    }

    /**
     * 聊天请求 DTO。
     */
    public record ChatRequest(String model, List<Message> messages, double temperature) {
    }

    /**
     * 消息 DTO。
     */
    public record Message(String role, String content) {
        public static Message ofSystem(String content) {
            return new Message("system", content);
        }

        public static Message ofUser(String content) {
            return new Message("user", content);
        }
    }

    /**
     * 聊天响应 DTO。
     */
    public record ChatResponse(List<Choice> choices) {
    }

    /**
     * 选择项 DTO。
     */
    public record Choice(Message message) {
    }
}
