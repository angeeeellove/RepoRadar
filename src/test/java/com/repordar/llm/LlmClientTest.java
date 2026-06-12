package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        var systemMsg = LlmClient.Message.ofSystem("You are a helpful assistant.");
        var userMsg = LlmClient.Message.ofUser("Hello");

        assertEquals("system", systemMsg.role());
        assertEquals("You are a helpful assistant.", systemMsg.content());
        assertEquals("user", userMsg.role());
        assertEquals("Hello", userMsg.content());
    }
}
