package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.VagueCommitDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 模糊提交扫描器，批量识别模糊提交。
 *
 * @author frank
 */
@Slf4j
@Component
public class LlmVagueScanner {

    private static final int BATCH_SIZE = 500;

    private static final String SCAN_PROMPT =
            "你是一个代码审查专家。分析以下 Git 提交列表，识别出消息模糊、描述不清的提交。\n\n" +
            "返回 JSON 数组，每个元素包含：\n" +
            "- hash: 提交哈希（短格式，7位）\n" +
            "- reason: 模糊原因（简短中文描述）\n\n" +
            "只返回 JSON 数组，不要其他内容。";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmVagueScanner(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量扫描模糊提交。
     *
     * @param commits    提交列表
     * @param baseUrl    LLM API 基础 URL
     * @param apiKey     LLM API 密钥
     * @param modelName  LLM 模型名称
     * @return 模糊提交列表（哈希和原因）
     */
    public List<VagueResult> scanVagueCommits(List<VagueCommitDto> commits,
                                               String baseUrl,
                                               String apiKey,
                                               String modelName) {
        if (commits.isEmpty()) {
            return List.of();
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("LLM API Key 未配置，跳过模糊提交扫描");
            return List.of();
        }

        List<VagueResult> results = new ArrayList<>();

        // 分批处理
        for (int i = 0; i < commits.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, commits.size());
            List<VagueCommitDto> batch = commits.subList(i, end);

            List<VagueResult> batchResults = scanBatch(batch, baseUrl, apiKey, modelName);
            results.addAll(batchResults);
        }

        return results;
    }

    private List<VagueResult> scanBatch(List<VagueCommitDto> batch,
                                        String baseUrl,
                                        String apiKey,
                                        String modelName) {
        try {
            StringBuilder promptBuilder = new StringBuilder(SCAN_PROMPT + "\n\n提交列表:\n");
            for (VagueCommitDto commit : batch) {
                promptBuilder.append(String.format("- %s: %s\n",
                        commit.getShortHash(), commit.getMessage()));
            }

            List<LlmClient.Message> messages = List.of(
                    LlmClient.Message.ofUser(promptBuilder.toString())
            );

            String response = llmClient.chat(baseUrl, apiKey, modelName, messages);
            return parseVagueResults(response);
        } catch (Exception e) {
            log.warn("LLM 模糊提交扫描失败: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<VagueResult> parseVagueResults(String response) {
        try {
            String json = extractJson(response);
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, VagueResult.class));
        } catch (Exception e) {
            log.warn("解析 LLM 模糊提交结果失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return "[]";
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

        // 提取第一个 [ 到最后一个 ] 之间的内容
        int bracketStart = response.indexOf('[');
        int bracketEnd = response.lastIndexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            return response.substring(bracketStart, bracketEnd + 1);
        }

        return "[]";
    }

    /**
     * 模糊提交结果 DTO。
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class VagueResult {
        private String hash;
        private String reason;
    }
}
