package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.CommitAnalysisDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmMapTranslator 单元测试，测试 JSON 反序列化容错。
 */
class LlmMapTranslatorTest {

    private LlmMapTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new LlmMapTranslator(null, new ObjectMapper());
    }

    @Test
    void shouldParseNormalJsonSuccessfully() {
        String response = """
                {
                    "intent": "修复用户登录超时问题",
                    "tags": ["BUGFIX", "PERF"],
                    "risk_level": "HIGH",
                    "message_quality": "EXCELLENT",
                    "quality_reason": "消息清晰，包含问题原因和修复方式"
                }
                """;

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals("修复用户登录超时问题", dto.getIntent());
            assertEquals(2, dto.getTags().size());
            assertTrue(dto.getTags().contains("BUGFIX"));
            assertTrue(dto.getTags().contains("PERF"));
            assertEquals("HIGH", dto.getRiskLevel());
            assertEquals("EXCELLENT", dto.getMessageQuality());
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldTolerateExtraFields() {
        String response = """
                {
                    "intent": "添加缓存功能",
                    "tags": ["FEATURE"],
                    "risk_level": "MEDIUM",
                    "message_quality": "GOOD",
                    "quality_reason": "描述合理",
                    "extra_field": "应该被忽略",
                    "another_extra": 123
                }
                """;

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals("添加缓存功能", dto.getIntent());
            assertEquals(1, dto.getTags().size());
            assertEquals("FEATURE", dto.getTags().get(0));
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldDegradeOnInvalidTags() {
        String response = """
                {
                    "intent": "更新配置",
                    "tags": ["CHORE", "INVALID_TAG", "FEATURE", "ALSO_INVALID"],
                    "risk_level": "LOW",
                    "message_quality": "POOR",
                    "quality_reason": "消息太简短"
                }
                """;

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals(2, dto.getTags().size());
            assertTrue(dto.getTags().contains("CHORE"));
            assertTrue(dto.getTags().contains("FEATURE"));
            assertFalse(dto.getTags().contains("INVALID_TAG"));
            assertFalse(dto.getTags().contains("ALSO_INVALID"));
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldKeepValidTags() {
        String response = """
                {
                    "intent": "重构用户模块",
                    "tags": ["REFACTOR", "CHORE"],
                    "risk_level": "MEDIUM",
                    "message_quality": "GOOD",
                    "quality_reason": "结构清晰"
                }
                """;

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals(2, dto.getTags().size());
            assertTrue(dto.getTags().contains("REFACTOR"));
            assertTrue(dto.getTags().contains("CHORE"));
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldHandleMarkdownWrappedJson() {
        String response = """
                ```json
                {
                    "intent": "优化查询性能",
                    "tags": ["PERF"],
                    "risk_level": "LOW",
                    "message_quality": "EXCELLENT",
                    "quality_reason": "详细说明优化措施"
                }
                ```
                """;

        try {
            var method = LlmMapTranslator.class.getDeclaredMethod("extractJsonObject", String.class);
            method.setAccessible(true);
            String json = (String) method.invoke(translator, response);

            assertTrue(json.contains("intent"));
            assertFalse(json.contains("```"));
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldDegradeOnMalformedJson() {
        String response = "{invalid json content";

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals("解析失败", dto.getIntent());
            assertEquals(1, dto.getTags().size());
            assertEquals("CHORE", dto.getTags().get(0));
            assertEquals("LOW", dto.getRiskLevel());
            assertEquals("VAGUE", dto.getMessageQuality());
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldSanitizeInvalidRiskLevel() {
        String response = """
                {
                    "intent": "文档更新",
                    "tags": ["DOCS"],
                    "risk_level": "INVALID_LEVEL",
                    "message_quality": "GOOD",
                    "quality_reason": "清晰的文档说明"
                }
                """;

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals("LOW", dto.getRiskLevel());
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldHandleEmptyTags() {
        String response = """
                {
                    "intent": "格式调整",
                    "tags": [],
                    "risk_level": "LOW",
                    "message_quality": "POOR",
                    "quality_reason": "无具体说明"
                }
                """;

        try {
            var parseMethod = LlmMapTranslator.class.getDeclaredMethod("parseAnalysisResult", String.class);
            parseMethod.setAccessible(true);
            CommitAnalysisDto dto = (CommitAnalysisDto) parseMethod.invoke(translator, response);

            assertNotNull(dto);
            assertEquals(1, dto.getTags().size());
            assertEquals("CHORE", dto.getTags().get(0));
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }

    @Test
    void shouldExtractJsonArrayFromResponse() {
        String response = """
                ```json
                [
                    {"hash": "abc1234", "intent": "修复登录", "tags": ["BUGFIX"], "risk_level": "HIGH", "message_quality": "GOOD"},
                    {"hash": "def5678", "intent": "优化查询", "tags": ["PERF"], "risk_level": "LOW", "message_quality": "EXCELLENT"}
                ]
                ```
                """;

        try {
            var method = LlmMapTranslator.class.getDeclaredMethod("extractJsonArray", String.class);
            method.setAccessible(true);
            String json = (String) method.invoke(translator, response);

            assertTrue(json.startsWith("["));
            assertTrue(json.contains("abc1234"));
            assertTrue(json.contains("def5678"));
            assertFalse(json.contains("```"));
        } catch (Exception e) {
            fail("测试失败: " + e.getMessage());
        }
    }
}
