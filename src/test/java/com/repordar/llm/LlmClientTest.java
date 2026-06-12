package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmClient 单元测试。
 */
class LlmClientTest {

    private LlmClient client;

    @BeforeEach
    void setUp() {
        client = new LlmClient(new ObjectMapper());
    }

    @Test
    void shouldCreateClientSuccessfully() {
        assertNotNull(client);
    }

    @Test
    void shouldCreateMessagesCorrectly() {
        LlmClient.Message systemMsg = LlmClient.Message.ofSystem("You are a helpful assistant.");
        LlmClient.Message userMsg = LlmClient.Message.ofUser("Hello");

        assertEquals("system", systemMsg.getRole());
        assertEquals("You are a helpful assistant.", systemMsg.getContent());
        assertEquals("user", userMsg.getRole());
        assertEquals("Hello", userMsg.getContent());
    }
}
