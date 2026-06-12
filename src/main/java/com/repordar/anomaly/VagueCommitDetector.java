package com.repordar.anomaly;

import com.repordar.dto.VagueCommitDto;
import com.repordar.git.CommitInfo;
import com.repordar.llm.LlmVagueScanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private final VagueScoringEngine scoringEngine;
    private final LlmVagueScanner llmVagueScanner;

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
     * @return 模糊提交列表
     */
    public List<CommitInfo> detect(List<CommitInfo> commits, boolean llmEnabled,
                                   Set<String> moduleNames,
                                   String llmKey, String llmUrl, String llmModel) {
        if (commits.isEmpty()) {
            return List.of();
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
     */
    private List<CommitInfo> detectWithLlm(List<CommitInfo> commits,
                                           String llmKey, String llmUrl, String llmModel) {
        log.info("使用 LLM 扫描模糊提交: {} 条", commits.size());

        // 转换为 VagueCommitDto（仅填 shortHash + message）
        List<VagueCommitDto> scanInput = commits.stream()
                .map(c -> new VagueCommitDto(
                        c.getHash(), c.getShortHash(),
                        null, null, null, c.getMessage(),
                        null, null, null))
                .collect(Collectors.toList());

        List<LlmVagueScanner.VagueResult> results =
                llmVagueScanner.scanVagueCommits(scanInput, llmUrl, llmKey, llmModel);

        if (results.isEmpty()) {
            log.info("LLM 未识别到模糊提交");
            return List.of();
        }

        // 用 LLM 返回的 hash 过滤原始提交
        Map<String, LlmVagueScanner.VagueResult> resultMap = results.stream()
                .collect(Collectors.toMap(
                        LlmVagueScanner.VagueResult::getHash,
                        Function.identity(),
                        (a, b) -> a));

        List<CommitInfo> vagueCommits = commits.stream()
                .filter(c -> resultMap.containsKey(c.getShortHash()))
                .collect(Collectors.toList());

        log.info("LLM 识别到 {} 条模糊提交", vagueCommits.size());
        return vagueCommits;
    }

    /**
     * 使用规则评分引擎检测模糊提交。
     */
    private List<CommitInfo> detectWithScoringEngine(List<CommitInfo> commits, Set<String> moduleNames) {
        return scoringEngine.scoreAndFilter(commits, moduleNames);
    }
}
