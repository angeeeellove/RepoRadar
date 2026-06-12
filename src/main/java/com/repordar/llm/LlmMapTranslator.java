package com.repordar.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.CommitAnalysisDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM Map 阶段翻译器，逐提交语义分析。
 *
 * @author frank
 */
@Slf4j
@Component
public class LlmMapTranslator {

    private static final int MAX_DIFF_CHARS = 2000;

    private static final String RISK_CRITICAL = "CRITICAL";
    private static final String RISK_HIGH = "HIGH";
    private static final String RISK_MEDIUM = "MEDIUM";
    private static final String RISK_LOW = "LOW";

    private static final String QUALITY_EXCELLENT = "EXCELLENT";
    private static final String QUALITY_GOOD = "GOOD";
    private static final String QUALITY_VAGUE = "VAGUE";
    private static final String QUALITY_POOR = "POOR";

    private static final String ANALYSIS_PROMPT =
            "你是一个代码审查专家。分析以下 Git 提交，提取语义信息。\n\n" +
            "提交信息：\n" +
            "- 作者: %s\n" +
            "- 日期: %s\n" +
            "- 消息: %s\n\n" +
            "代码变更：\n%s\n\n" +
            "返回 JSON 对象，包含以下字段：\n" +
            "- intent: 提交意图（简短中文描述）\n" +
            "- tags: 标签列表，可选值：[FEATURE, BUGFIX, REFACTOR, CHORE, TEST, DOCS, PERF, STYLE]\n" +
            "- risk_level: 风险等级，可选值：[CRITICAL, HIGH, MEDIUM, LOW]\n" +
            "- message_quality: 消息质量，可选值：[EXCELLENT, GOOD, VAGUE, POOR]\n" +
            "- quality_reason: 质量评估原因（中文描述）\n\n" +
            "只返回 JSON 对象，不要其他内容。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmMapTranslator(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析单个提交。
     *
     * @param author     作者
     * @param date       日期
     * @param message    提交消息
     * @param diff       代码变更差异
     * @param baseUrl    LLM API 基础 URL
     * @param apiKey     LLM API 密钥
     * @param modelName  LLM 模型名称
     * @return 提交分析结果
     */
    public CommitAnalysisDto analyzeCommit(String author,
                                            String date,
                                            String message,
                                            String diff,
                                            String baseUrl,
                                            String apiKey,
                                            String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API Key 未配置，返回默认分析结果");
            return createDefaultDto();
        }

        try {
            String truncatedDiff = truncateDiff(diff);
            String prompt = String.format(ANALYSIS_PROMPT,
                    author, date, message, truncatedDiff);

            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.ofUser(prompt)
            );

            String response = llmClient.chat(baseUrl, apiKey, modelName, messages);
            return parseAnalysisResult(response);
        } catch (Exception e) {
            log.warn("LLM 提交分析失败，降级为默认值: {}", e.getMessage());
            return createDefaultDto();
        }
    }

    /**
     * 截断 Diff 文本。
     */
    private String truncateDiff(String diff) {
        if (diff == null || diff.length() <= MAX_DIFF_CHARS) {
            return diff;
        }
        return diff.substring(0, MAX_DIFF_CHARS) + "\n... (截断)";
    }

    /**
     * 解析 LLM 返回的分析结果。
     */
    private CommitAnalysisDto parseAnalysisResult(String response) {
        try {
            String json = extractJson(response);
            CommitAnalysisDto dto = objectMapper.readValue(json, CommitAnalysisDto.class);

            // 检查是否所有字段都为空（可能是空 JSON {}）
            if (dto.getIntent() == null && dto.getTags() == null
                    && dto.getRiskLevel() == null && dto.getMessageQuality() == null) {
                return createDefaultDto();
            }

            // 枚举值校验与修正
            dto.setTags(sanitizeTags(dto.getTags()));
            dto.setRiskLevel(sanitizeRiskLevel(dto.getRiskLevel()));
            dto.setMessageQuality(sanitizeMessageQuality(dto.getMessageQuality()));
            // 设置默认意图
            if (dto.getIntent() == null || dto.getIntent().isBlank()) {
                dto.setIntent("解析失败");
            }

            return dto;
        } catch (JsonProcessingException e) {
            log.warn("LLM JSON 解析失败，降级为默认值: {}", e.getMessage());
            return createDefaultDto();
        }
    }

    /**
     * 从 LLM 响应中提取有效 JSON。
     * 三步防线：① 去除 markdown 代码块 ② 提取花括号范围 ③ 返回原始文本兜底
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }

        // 防线 1：去除 ```json ... ``` 包裹
        int codeStart = response.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = response.indexOf('\n', codeStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }

        // 防线 2：提取第一个 { 到最后一个 } 之间的内容
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        // 防线 3：兜底返回空 JSON
        return "{}";
    }

    /**
     * 校验并修正标签列表。
     */
    private List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of("CHORE");
        }

        return tags.stream()
                .filter(this::isValidTag)
                .toList();
    }

    private boolean isValidTag(String tag) {
        return tag != null && List.of("FEATURE", "BUGFIX", "REFACTOR",
                "CHORE", "TEST", "DOCS", "PERF", "STYLE").contains(tag.toUpperCase());
    }

    /**
     * 校验并修正风险等级。
     */
    private String sanitizeRiskLevel(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return RISK_LOW;
        }

        String upper = riskLevel.toUpperCase();
        if (List.of(RISK_CRITICAL, RISK_HIGH, RISK_MEDIUM, RISK_LOW).contains(upper)) {
            return upper;
        }
        return RISK_LOW;
    }

    /**
     * 校验并修正消息质量。
     */
    private String sanitizeMessageQuality(String messageQuality) {
        if (messageQuality == null || messageQuality.isBlank()) {
            return QUALITY_VAGUE;
        }

        String upper = messageQuality.toUpperCase();
        if (List.of(QUALITY_EXCELLENT, QUALITY_GOOD, QUALITY_VAGUE, QUALITY_POOR).contains(upper)) {
            return upper;
        }
        return QUALITY_VAGUE;
    }

    /**
     * 创建默认 DTO（降级时使用）。
     */
    private CommitAnalysisDto createDefaultDto() {
        return new CommitAnalysisDto(
                "解析失败",
                List.of("CHORE"),
                RISK_LOW,
                QUALITY_VAGUE,
                "LLM 分析失败"
        );
    }
}
