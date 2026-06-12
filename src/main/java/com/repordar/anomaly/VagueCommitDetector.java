package com.repordar.anomaly;

import com.repordar.dto.VagueCommitDto;
import com.repordar.git.CommitInfo;
import com.repordar.llm.LlmVagueScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模糊提交检测器，支持双模式编排。
 * <p>
 * LLM 模式使用 LlmVagueScanner 批量语义扫描，降级模式使用 VagueScoringEngine 规则评分。
 *
 * @author frank
 */
@Slf4j
@Component
public class VagueCommitDetector {

    /** Conventional Commits 格式中 description 部分的最小长度阈值（中文信息密度高，阈值较低） */
    private static final int MIN_CLEAR_DESCRIPTION_LENGTH = 6;
    /** 非格式消息的最小长度阈值 */
    private static final int MIN_PLAIN_MESSAGE_LENGTH = 15;
    /** 非格式消息中中文最小字符数 */
    private static final int MIN_PLAIN_CHINESE_COUNT = 4;
    /** CC 前缀匹配：提取 description 部分的最大长度（安全截断防止极端情况） */
    private static final int MAX_CC_DESCRIPTION_LENGTH = 500;

    private final VagueScoringEngine scoringEngine;
    private final LlmVagueScanner llmVagueScanner;

    /**
     * 检测结果，携带模糊提交列表和每条提交的模糊原因。
     */
    public static class DetectResult {
        private final List<CommitInfo> commits;
        private final Map<String, String> reasons;

        public DetectResult(List<CommitInfo> commits, Map<String, String> reasons) {
            this.commits = commits;
            this.reasons = reasons;
        }

        public List<CommitInfo> getCommits() {
            return commits;
        }

        public Map<String, String> getReasons() {
            return reasons;
        }
    }

    /**
     * 构造模糊提交检测器。
     *
     * @param scoringEngine    规则评分引擎（降级模式）
     * @param llmVagueScanner  LLM 模糊扫描器（可为 null，测试降级场景）
     */
    public VagueCommitDetector(VagueScoringEngine scoringEngine, LlmVagueScanner llmVagueScanner) {
        this.scoringEngine = scoringEngine;
        this.llmVagueScanner = llmVagueScanner;
    }

    /**
     * 检测模糊提交。
     *
     * @param commits     提交列表
     * @param llmEnabled  是否启用 LLM
     * @param moduleNames 模块名集合
     * @param llmKey      LLM API Key（可为 null）
     * @param llmUrl      LLM API URL（可为 null）
     * @param llmModel    LLM 模型名（可为 null）
     * @return 检测结果（包含模糊提交列表和 hash→reason 映射）
     */
    public DetectResult detect(List<CommitInfo> commits, boolean llmEnabled,
                               Set<String> moduleNames,
                               String llmKey, String llmUrl, String llmModel) {
        if (commits.isEmpty()) {
            return new DetectResult(List.of(), Map.of());
        }

        if (llmEnabled && llmVagueScanner != null && llmKey != null && !llmKey.isBlank()) {
            return detectWithLlm(commits, llmKey, llmUrl, llmModel);
        }

        if (llmEnabled) {
            log.warn("LLM 模式启用但 LlmVagueScanner 或 API Key 不可用，降级为规则评分");
        }

        return detectWithScoringEngine(commits, moduleNames);
    }

    /**
     * 使用 LLM 批量扫描模糊提交。
     * 先用规则预过滤，排除明显清晰的提交（CC 格式 + 有具体描述），再交给 LLM 判断。
     */
    private DetectResult detectWithLlm(List<CommitInfo> commits,
                                       String llmKey, String llmUrl, String llmModel) {
        log.info("使用 LLM 扫描模糊提交: {} 条", commits.size());

        // 规则预过滤：排除明显清晰的提交
        List<CommitInfo> candidates = new ArrayList<>();
        int prefiltered = 0;
        for (CommitInfo c : commits) {
            if (isDefinitelyClear(c.getMessage())) {
                prefiltered++;
            } else {
                candidates.add(c);
            }
        }

        if (prefiltered > 0) {
            log.info("规则预过滤排除 {} 条清晰提交，剩余 {} 条待 LLM 判断", prefiltered, candidates.size());
        }

        if (candidates.isEmpty()) {
            log.info("所有提交均被规则预过滤判定为清晰，无需 LLM 扫描");
            return new DetectResult(List.of(), Map.of());
        }

        // 转换为 VagueCommitDto（仅填 shortHash + message）
        List<VagueCommitDto> scanInput = candidates.stream()
                .map(c -> new VagueCommitDto(
                        c.getHash(), c.getShortHash(),
                        null, null, null, c.getMessage(),
                        null, null, null))
                .collect(Collectors.toList());

        List<LlmVagueScanner.VagueResult> results =
                llmVagueScanner.scanVagueCommits(scanInput, llmUrl, llmKey, llmModel);

        if (results.isEmpty()) {
            log.info("LLM 未识别到模糊提交");
            return new DetectResult(List.of(), Map.of());
        }

        // LLM 返回的 hash → reason 映射（key 为 shortHash）
        Map<String, String> reasonMap = results.stream()
                .collect(Collectors.toMap(
                        LlmVagueScanner.VagueResult::getHash,
                        LlmVagueScanner.VagueResult::getReason,
                        (a, b) -> a));

        List<CommitInfo> vagueCommits = candidates.stream()
                .filter(c -> reasonMap.containsKey(c.getShortHash()))
                .collect(Collectors.toList());

        log.info("LLM 识别到 {} 条模糊提交", vagueCommits.size());
        return new DetectResult(vagueCommits, reasonMap);
    }

    /**
     * 规则判断提交信息是否一定清晰（不可能是模糊提交）。
     * <p>
     * 判断逻辑：
     * 1. Conventional Commits 格式 + description 超过阈值 → 一定清晰
     * 2. 非格式消息但足够长且包含中文功能描述 → 大概率清晰
     *
     * @param message 提交信息
     * @return true 表示一定清晰，不需要 LLM 判断
     */
    boolean isDefinitelyClear(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        // 去除 emoji 前缀（如 ✨ feat(...)）
        String stripped = message.trim().replaceAll("^[\\p{So}\\p{Sk}]+\\s*", "");
        // 去除方括号标签（如 [feature] xxx）
        stripped = stripped.replaceAll("^\\[[^\\]]+\\]\\s*", "");

        // 检查 Conventional Commits 格式（纯字符串操作，避免正则 ReDOS）
        String description = extractCcDescription(stripped);
        if (description != null && description.length() > MIN_CLEAR_DESCRIPTION_LENGTH) {
            return true;
        }

        // 非格式消息：如果足够长且包含中文字符，大概率清晰
        if (stripped.length() > MIN_PLAIN_MESSAGE_LENGTH) {
            long chineseCount = stripped.chars()
                    .filter(ch -> ch >= 0x4E00 && ch <= 0x9FFF)
                    .count();
            if (chineseCount >= MIN_PLAIN_CHINESE_COUNT) {
                return true;
            }
        }

        return false;
    }

    /**
     * 从提交信息中提取 CC 格式的 description 部分（纯字符串操作）。
     * 支持：type: desc 和 type(scope): desc 格式。
     *
     * @param message 提交信息
     * @return description 文本，非 CC 格式返回 null
     */
    private String extractCcDescription(String message) {
        int colonIndex = message.indexOf(':');
        if (colonIndex < 0 || colonIndex >= message.length() - 1) {
            return null;
        }

        String prefix = message.substring(0, colonIndex);
        if (!isConventionalPrefix(prefix)) {
            return null;
        }

        String desc = message.substring(colonIndex + 1).trim();
        // 安全截断，防止极端长度
        if (desc.length() > MAX_CC_DESCRIPTION_LENGTH) {
            desc = desc.substring(0, MAX_CC_DESCRIPTION_LENGTH);
        }
        return desc;
    }

    /**
     * 校验前缀是否为 Conventional Commits 格式：type 或 type(scope)。
     */
    private boolean isConventionalPrefix(String prefix) {
        int openParen = prefix.indexOf('(');
        if (openParen < 0) {
            // 纯 type: 仅允许字母数字
            return !prefix.isEmpty() && prefix.chars().allMatch(Character::isLetterOrDigit);
        }
        int closeParen = prefix.indexOf(')', openParen);
        if (closeParen != prefix.length() - 1) {
            return false;
        }
        String type = prefix.substring(0, openParen);
        String scope = prefix.substring(openParen + 1, closeParen);
        return !type.isEmpty()
                && type.chars().allMatch(Character::isLetterOrDigit)
                && !scope.isEmpty()
                && scope.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-');
    }

    /**
     * 使用规则评分引擎检测模糊提交。
     */
    private DetectResult detectWithScoringEngine(List<CommitInfo> commits, Set<String> moduleNames) {
        List<CommitInfo> vagueCommits = scoringEngine.scoreAndFilter(commits, moduleNames);
        return new DetectResult(vagueCommits, Map.of());
    }
}
