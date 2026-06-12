package com.repordar.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.AuthorStatsDto;
import com.repordar.dto.CommitAnalysisDto;
import com.repordar.dto.GlobalInsightDto;
import com.repordar.dto.ModuleStatsDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM Reduce 阶段分析器，生成全局洞察。
 *
 * @author frank
 */
@Slf4j
@Component
public class LlmReduceAnalyzer {

    private static final int MIN_HEALTH_SCORE = 0;
    private static final int MAX_HEALTH_SCORE = 100;

    private static final String INSIGHT_PROMPT =
            "你是一个代码库分析专家。根据以下统计数据，生成代码库健康报告。\n\n" +
            "作者统计：\n%s\n\n" +
            "模块统计：\n%s\n\n" +
            "%s" +
            "返回 JSON 对象，包含以下字段：\n" +
            "- summary: 整体总结（简短中文描述，50字以内）\n" +
            "- recommendations: 改进建议列表（中文字符串数组）\n" +
            "- health_score: 健康评分（0-100整数）\n\n" +
            "只返回 JSON 对象，不要其他内容。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmReduceAnalyzer(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成全局洞察。
     *
     * @param authorStats  作者统计列表
     * @param moduleStats  模块统计列表
     * @param analysisMap  LLM Map 分析结果（key=commit hash），可为空
     * @param baseUrl      LLM API 基础 URL
     * @param apiKey       LLM API 密钥
     * @param modelName    LLM 模型名称
     * @return 全局洞察
     */
    public GlobalInsightDto generateInsight(List<AuthorStatsDto> authorStats,
                                             List<ModuleStatsDto> moduleStats,
                                             Map<String, CommitAnalysisDto> analysisMap,
                                             String baseUrl,
                                             String apiKey,
                                             String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API Key 未配置，返回默认洞察");
            return createDefaultInsight();
        }

        try {
            String authorStatsText = formatAuthorStats(authorStats);
            String moduleStatsText = formatModuleStats(moduleStats);
            String analysisSummary = formatAnalysisSummary(analysisMap);
            String prompt = String.format(INSIGHT_PROMPT, authorStatsText, moduleStatsText, analysisSummary);

            var messages = List.of(
                    LlmClient.Message.ofUser(prompt)
            );

            String response = llmClient.chat(baseUrl, apiKey, modelName, messages);
            return parseInsightResult(response);
        } catch (Exception e) {
            log.warn("LLM 全局洞察生成失败，降级为默认值: {}", e.getMessage());
            return createDefaultInsight();
        }
    }

    /**
     * 格式化作者统计。
     */
    private String formatAuthorStats(List<AuthorStatsDto> authorStats) {
        if (authorStats == null || authorStats.isEmpty()) {
            return "无数据";
        }

        StringBuilder sb = new StringBuilder();
        for (AuthorStatsDto stats : authorStats) {
            sb.append(String.format("- %s: %d 次提交, %d 行增加, %d 行删除\n",
                    stats.getName(), stats.getCommitCount(),
                    stats.getLinesAdded(), stats.getLinesDeleted()));
        }
        return sb.toString();
    }

    /**
     * 格式化模块统计。
     */
    private String formatModuleStats(List<ModuleStatsDto> moduleStats) {
        if (moduleStats == null || moduleStats.isEmpty()) {
            return "无数据";
        }

        StringBuilder sb = new StringBuilder();
        for (ModuleStatsDto stats : moduleStats) {
            sb.append(String.format("- %s: %d 次提交, %d 行变更\n",
                    stats.getName(), stats.getCommitCount(), stats.getLinesChanged()));
        }
        return sb.toString();
    }

    /**
     * 格式化 Map 阶段分析摘要，注入 Reduce prompt。
     */
    private String formatAnalysisSummary(Map<String, CommitAnalysisDto> analysisMap) {
        if (analysisMap == null || analysisMap.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("异常提交分析摘要：\n");
        for (Map.Entry<String, CommitAnalysisDto> entry : analysisMap.entrySet()) {
            String shortHash = entry.getKey().length() > 7
                    ? entry.getKey().substring(0, 7) : entry.getKey();
            CommitAnalysisDto a = entry.getValue();
            sb.append(String.format("- %s: intent=%s, risk=%s, quality=%s\n",
                    shortHash,
                    a.getIntent() != null ? a.getIntent() : "未知",
                    a.getRiskLevel() != null ? a.getRiskLevel() : "未知",
                    a.getMessageQuality() != null ? a.getMessageQuality() : "未知"));
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的洞察结果。
     */
    private GlobalInsightDto parseInsightResult(String response) {
        try {
            String json = extractJson(response);
            GlobalInsightDto dto = objectMapper.readValue(json, GlobalInsightDto.class);

            // 确保评分在有效范围内
            if (dto.getHealthScore() < MIN_HEALTH_SCORE) {
                dto.setHealthScore(MIN_HEALTH_SCORE);
            } else if (dto.getHealthScore() > MAX_HEALTH_SCORE) {
                dto.setHealthScore(MAX_HEALTH_SCORE);
            }

            return dto;
        } catch (JsonProcessingException e) {
            log.warn("LLM JSON 解析失败，降级为默认值: {}", e.getMessage());
            return createDefaultInsight();
        }
    }

    /**
     * 从 LLM 响应中提取有效 JSON。
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "{}";
        }

        // 去除 ```json ... ``` 包裹
        int codeStart = response.indexOf("```json");
        if (codeStart >= 0) {
            int jsonStart = response.indexOf('\n', codeStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }

        // 提取第一个 { 到最后一个 } 之间的内容
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1);
        }

        return "{}";
    }

    /**
     * 创建默认洞察（降级时使用）。
     */
    private GlobalInsightDto createDefaultInsight() {
        return new GlobalInsightDto(
                "LLM 分析失败，使用默认总结",
                List.of("建议配置 LLM API 以获取更准确的洞察"),
                60
        );
    }
}
