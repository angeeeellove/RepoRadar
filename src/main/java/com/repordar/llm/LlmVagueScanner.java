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
            "你是 Git 提交信息质量评审专家。请【严格保守】地判断提交信息是否模糊。\n\n" +
            "核心原则：宁可漏判也不误判。只有真正无法理解提交做了什么的才标记为模糊。\n\n" +
            "✅ 以下类型【一定清晰】，绝对不要标记为模糊：\n" +
            "- 任何 Conventional Commits 格式（含 emoji 前缀）：\"feat(order): 添加订单创建功能\"、\"✨ feat(git): 实现仓库克隆\"\n" +
            "- 说明了涉及的功能/模块/组件：\"更新模糊检测和管线测试\"、\"重构订单处理流程\"\n" +
            "- 说明了修复的具体问题：\"修复用户登录 NPE\"、\"解决支付超时问题\"\n" +
            "- 提到了具体的技术点：\"添加 pre-commit hook 自动拦截\"、\"实现滑动窗口算法\"\n" +
            "- 描述了做了什么操作+作用对象：\"实现模糊提交评分引擎\"、\"提取 JSON 解析逻辑\"\n\n" +
            "❌ 以下才是【真正的模糊提交】：\n" +
            "- 只有孤立的动词/泛词：\"优化\"、\"调整\"、\"fix\"、\"update\"、\"wip\"\n" +
            "- 有动词但完全没有作用对象：\"优化了代码\"、\"修复问题\"、\"小改动\"、\"修改了一下\"\n" +
            "- 无意义的占位信息：\"test\"、\"temp\"、\"done\"、\".\"\n\n" +
            "判断方法：问自己「不看代码，仅凭这条消息能知道这次提交涉及什么功能/模块/问题吗？」如果答案是能，就不是模糊。\n\n" +
            "从提交列表中识别真正模糊的提交，返回 JSON 数组：\n" +
            "[{\"hash\": \"短哈希\", \"reason\": \"模糊原因\"}]\n\n" +
            "只返回 JSON 数组。没有模糊提交则返回 []。";

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
