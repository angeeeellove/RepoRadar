package com.repordar.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.CommitAnalysisDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM Map 阶段翻译器，支持批量语义分析。
 * <p>
 * 将多条提交一次性发给 LLM，返回 JSON 数组，减少 API 调用次数。
 * 同时保留单条分析方法供降级场景使用。
 *
 * @author frank
 */
@Slf4j
@Component
public class LlmMapTranslator {

    private static final int MAX_DIFF_CHARS = 2000;
    /** 批量分析每批最大提交数（控制 prompt 长度） */
    private static final int BATCH_SIZE = 10;

    private static final String RISK_CRITICAL = "CRITICAL";
    private static final String RISK_HIGH = "HIGH";
    private static final String RISK_MEDIUM = "MEDIUM";
    private static final String RISK_LOW = "LOW";

    private static final String QUALITY_EXCELLENT = "EXCELLENT";
    private static final String QUALITY_GOOD = "GOOD";
    private static final String QUALITY_VAGUE = "VAGUE";
    private static final String QUALITY_POOR = "POOR";

    private static final String BATCH_ANALYSIS_PROMPT =
            "你是代码审查专家。批量分析以下 Git 提交，提取语义信息。\n\n" +
            "提交列表:\n%s\n" +
            "对每条提交返回 JSON 对象，包含：\n" +
            "- hash: 提交短哈希\n" +
            "- intent: 提交意图（简短中文）\n" +
            "- tags: 标签列表 [FEATURE, BUGFIX, REFACTOR, CHORE, TEST, DOCS, PERF, STYLE]\n" +
            "- risk_level: 风险等级 [CRITICAL, HIGH, MEDIUM, LOW]\n" +
            "- message_quality: 消息质量 [EXCELLENT, GOOD, VAGUE, POOR]\n" +
            "- quality_reason: 质量评估原因（中文）\n\n" +
            "返回 JSON 数组，每条提交一个对象。只返回 JSON，不要其他内容。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmMapTranslator(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量分析提交（分批调用 LLM）。
     *
     * @param commitDataMap key=短哈希, value=[作者, 日期, 消息, diff摘要]
     * @param baseUrl       LLM API 基础 URL
     * @param apiKey        LLM API 密钥
     * @param modelName     LLM 模型名称
     * @return 短哈希 → CommitAnalysisDto 映射
     */
    public Map<String, CommitAnalysisDto> analyzeCommitsBatch(
            Map<String, String[]> commitDataMap,
            String baseUrl, String apiKey, String modelName) {

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API Key 未配置，跳过批量分析");
            return Map.of();
        }

        if (commitDataMap == null || commitDataMap.isEmpty()) {
            return Map.of();
        }

        Map<String, CommitAnalysisDto> results = new java.util.LinkedHashMap<>();
        List<Map.Entry<String, String[]>> entries = new ArrayList<>(commitDataMap.entrySet());

        // 分批处理
        for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, entries.size());
            List<Map.Entry<String, String[]>> batch = entries.subList(i, end);

            Map<String, CommitAnalysisDto> batchResult =
                    analyzeOneBatch(batch, baseUrl, apiKey, modelName);
            results.putAll(batchResult);
        }

        return results;
    }

    /**
     * 分析单个批次。
     */
    private Map<String, CommitAnalysisDto> analyzeOneBatch(
            List<Map.Entry<String, String[]>> batch,
            String baseUrl, String apiKey, String modelName) {

        // 构建提交列表文本
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> entry : batch) {
            String hash = entry.getKey();
            String[] data = entry.getValue();
            String author = data[0];
            String date = data[1];
            String message = data[2];
            String diff = truncateDiff(data[3]);

            sb.append(String.format(
                    "- hash: %s | 作者: %s | 日期: %s | 消息: %s\n  变更: %s\n",
                    hash, author, date, message, diff));
        }

        String prompt = String.format(BATCH_ANALYSIS_PROMPT, sb.toString());

        try {
            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.ofUser(prompt));
            String response = llmClient.chat(baseUrl, apiKey, modelName, messages);
            return parseBatchResult(response, batch);
        } catch (Exception e) {
            log.warn("LLM 批量分析失败（{} 条），降级为逐条分析: {}",
                    batch.size(), e.getMessage());
            // 降级：逐条分析
            return fallbackSingleAnalysis(batch, baseUrl, apiKey, modelName);
        }
    }

    /**
     * 解析批量分析结果（JSON 数组）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, CommitAnalysisDto> parseBatchResult(
            String response, List<Map.Entry<String, String[]>> batch) {

        Map<String, CommitAnalysisDto> results = new java.util.LinkedHashMap<>();
        try {
            String json = extractJsonArray(response);
            List<BatchItem> items = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, BatchItem.class));

            // 建立 hash → BatchItem 映射
            for (BatchItem item : items) {
                if (item.getHash() != null) {
                    results.put(item.getHash(), sanitizeBatchItem(item));
                }
            }
        } catch (Exception e) {
            log.warn("批量结果 JSON 解析失败，降级为逐条: {}", e.getMessage());
            return Map.of();
        }

        // 检查是否有遗漏的提交
        for (Map.Entry<String, String[]> entry : batch) {
            if (!results.containsKey(entry.getKey())) {
                log.debug("批量分析遗漏提交 {}，使用默认值", entry.getKey());
                results.put(entry.getKey(), createDefaultDto());
            }
        }

        log.info("批量分析: {}/{} 条成功", results.size(), batch.size());
        return results;
    }

    /**
     * 降级：逐条分析（批量失败时兜底）。
     */
    private Map<String, CommitAnalysisDto> fallbackSingleAnalysis(
            List<Map.Entry<String, String[]>> batch,
            String baseUrl, String apiKey, String modelName) {

        Map<String, CommitAnalysisDto> results = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : batch) {
            String[] data = entry.getValue();
            CommitAnalysisDto dto = analyzeCommit(
                    data[0], data[1], data[2], data[3],
                    baseUrl, apiKey, modelName);
            results.put(entry.getKey(), dto);
        }
        return results;
    }

    /**
     * 分析单个提交（保留原有接口，供降级和外部调用使用）。
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
            String prompt = String.format(
                    "你是代码审查专家。分析以下 Git 提交，提取语义信息。\n\n" +
                    "提交信息：\n- 作者: %s\n- 日期: %s\n- 消息: %s\n\n" +
                    "代码变更：\n%s\n\n" +
                    "返回 JSON 对象：\n" +
                    "- intent: 提交意图（简短中文）\n" +
                    "- tags: 标签列表 [FEATURE, BUGFIX, REFACTOR, CHORE, TEST, DOCS, PERF, STYLE]\n" +
                    "- risk_level: 风险等级 [CRITICAL, HIGH, MEDIUM, LOW]\n" +
                    "- message_quality: 消息质量 [EXCELLENT, GOOD, VAGUE, POOR]\n" +
                    "- quality_reason: 质量评估原因（中文）\n\n" +
                    "只返回 JSON 对象。",
                    author, date, message, truncateDiff(diff));

            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.ofUser(prompt));

            String response = llmClient.chat(baseUrl, apiKey, modelName, messages);
            return parseAnalysisResult(response);
        } catch (Exception e) {
            log.warn("LLM 提交分析失败，降级为默认值: {}", e.getMessage());
            return createDefaultDto();
        }
    }

    // ==================== JSON 提取 ====================

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
     * 从 LLM 响应中提取 JSON 对象。
     */
    private String extractJsonObject(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }

        int codeStart = response.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = response.indexOf('\n', codeStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }

        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        return "{}";
    }

    /**
     * 从 LLM 响应中提取 JSON 数组。
     */
    private String extractJsonArray(String response) {
        if (response == null || response.isBlank()) {
            return "[]";
        }

        int codeStart = response.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = response.indexOf('\n', codeStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }

        int bracketStart = response.indexOf('[');
        int bracketEnd = response.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return response.substring(bracketStart, bracketEnd + 1);
        }

        return "[]";
    }

    // ==================== 结果解析与校验 ====================

    /**
     * 解析单条分析结果。
     */
    private CommitAnalysisDto parseAnalysisResult(String response) {
        try {
            String json = extractJsonObject(response);
            CommitAnalysisDto dto = objectMapper.readValue(json, CommitAnalysisDto.class);

            if (dto.getIntent() == null && dto.getTags() == null
                    && dto.getRiskLevel() == null && dto.getMessageQuality() == null) {
                return createDefaultDto();
            }

            dto.setTags(sanitizeTags(dto.getTags()));
            dto.setRiskLevel(sanitizeRiskLevel(dto.getRiskLevel()));
            dto.setMessageQuality(sanitizeMessageQuality(dto.getMessageQuality()));
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
     * 将批量结果项转换为 CommitAnalysisDto 并校验。
     */
    private CommitAnalysisDto sanitizeBatchItem(BatchItem item) {
        CommitAnalysisDto dto = new CommitAnalysisDto();
        dto.setIntent(item.getIntent() != null ? item.getIntent() : "解析失败");
        dto.setTags(sanitizeTags(item.getTags()));
        dto.setRiskLevel(sanitizeRiskLevel(item.getRiskLevel()));
        dto.setMessageQuality(sanitizeMessageQuality(item.getMessageQuality()));
        dto.setQualityReason(item.getQualityReason());
        return dto;
    }

    private List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of("CHORE");
        }
        return tags.stream().filter(this::isValidTag).toList();
    }

    private boolean isValidTag(String tag) {
        return tag != null && List.of("FEATURE", "BUGFIX", "REFACTOR",
                "CHORE", "TEST", "DOCS", "PERF", "STYLE").contains(tag.toUpperCase());
    }

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

    private CommitAnalysisDto createDefaultDto() {
        return new CommitAnalysisDto(
                "解析失败",
                List.of("CHORE"),
                RISK_LOW,
                QUALITY_VAGUE,
                "LLM 分析失败"
        );
    }

    /**
     * 批量分析结果内部 DTO（含 hash 字段用于匹配）。
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchItem {
        private String hash;
        private String intent;
        private List<String> tags;
        @com.fasterxml.jackson.annotation.JsonAlias("risk_level")
        private String riskLevel;
        @com.fasterxml.jackson.annotation.JsonAlias("message_quality")
        private String messageQuality;
        @com.fasterxml.jackson.annotation.JsonAlias("quality_reason")
        private String qualityReason;
    }
}
