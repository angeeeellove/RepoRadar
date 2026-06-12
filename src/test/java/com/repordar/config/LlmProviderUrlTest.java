package com.repordar.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM Base URL 自动推断测试。
 * <p>
 * 用户传入完整模型产品名（如 GLM-4.7、deepseek-chat），
 * 系统根据模型名中包含的厂商标识自动匹配 Base URL。
 *
 * @author frank
 */
class LlmProviderUrlTest {

    // ==================== DeepSeek ====================

    @Test
    void shouldResolveDeepseekChat() {
        assertEquals("https://api.deepseek.com/v1",
                AppProperties.Llm.resolveBaseUrl("deepseek-chat", null));
    }

    @Test
    void shouldResolveDeepseekReasoner() {
        assertEquals("https://api.deepseek.com/v1",
                AppProperties.Llm.resolveBaseUrl("deepseek-reasoner", null));
    }

    // ==================== OpenAI ====================

    @Test
    void shouldResolveGpt4o() {
        assertEquals("https://api.openai.com/v1",
                AppProperties.Llm.resolveBaseUrl("gpt-4o", null));
    }

    @Test
    void shouldResolveGpt35Turbo() {
        assertEquals("https://api.openai.com/v1",
                AppProperties.Llm.resolveBaseUrl("gpt-3.5-turbo", null));
    }

    @Test
    void shouldResolveO1Preview() {
        assertEquals("https://api.openai.com/v1",
                AppProperties.Llm.resolveBaseUrl("o1-preview", null));
    }

    @Test
    void shouldResolveO3Mini() {
        assertEquals("https://api.openai.com/v1",
                AppProperties.Llm.resolveBaseUrl("o3-mini", null));
    }

    // ==================== Anthropic Claude ====================

    @Test
    void shouldResolveClaude3Opus() {
        assertEquals("https://api.anthropic.com/v1",
                AppProperties.Llm.resolveBaseUrl("claude-3-opus-20240229", null));
    }

    @Test
    void shouldResolveClaudeSonnet4() {
        assertEquals("https://api.anthropic.com/v1",
                AppProperties.Llm.resolveBaseUrl("claude-sonnet-4-20250514", null));
    }

    // ==================== 智谱 GLM ====================

    @Test
    void shouldResolveGlm4() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4",
                AppProperties.Llm.resolveBaseUrl("GLM-4", null));
    }

    @Test
    void shouldResolveGlm47() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4",
                AppProperties.Llm.resolveBaseUrl("GLM-4.7", null));
    }

    @Test
    void shouldResolveChatglm() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4",
                AppProperties.Llm.resolveBaseUrl("chatglm-turbo", null));
    }

    // ==================== 阿里通义千问 ====================

    @Test
    void shouldResolveQwenMax() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                AppProperties.Llm.resolveBaseUrl("qwen-max", null));
    }

    @Test
    void shouldResolveQwq32b() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                AppProperties.Llm.resolveBaseUrl("qwq-32b", null));
    }

    // ==================== Moonshot / Kimi ====================

    @Test
    void shouldResolveMoonshot() {
        assertEquals("https://api.moonshot.cn/v1",
                AppProperties.Llm.resolveBaseUrl("moonshot-v1-8k", null));
    }

    // ==================== 兜底逻辑 ====================

    @Test
    void shouldReturnNullForUnknownModel() {
        assertNull(AppProperties.Llm.resolveBaseUrl("my-custom-model", null));
    }

    @Test
    void shouldPreferExplicitUrlOverAutoDetection() {
        assertEquals("https://my-proxy.com/v1",
                AppProperties.Llm.resolveBaseUrl("deepseek-chat", "https://my-proxy.com/v1"));
    }

    @Test
    void shouldReturnNullWhenModelIsNull() {
        assertNull(AppProperties.Llm.resolveBaseUrl(null, null));
    }

    @Test
    void shouldUseExplicitUrlEvenForUnknownModel() {
        assertEquals("https://my-api.com/v1",
                AppProperties.Llm.resolveBaseUrl("unknown-model", "https://my-api.com/v1"));
    }
}
