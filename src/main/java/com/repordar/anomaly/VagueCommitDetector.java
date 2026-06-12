package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Vague commit detector with dual-mode orchestration.
 * <p>
 * Uses LlmVagueScanner when LLM is enabled, otherwise uses VagueScoringEngine.
 *
 * @author frank
 */
@Slf4j
@Component
public class VagueCommitDetector {

    private final VagueScoringEngine scoringEngine;
    // LLM layer not yet implemented, left empty for now
    // private final LlmVagueScanner llmVagueScanner;

    public VagueCommitDetector(VagueScoringEngine scoringEngine) {
        this.scoringEngine = scoringEngine;
    }

    /**
     * Detect vague commits in commit list.
     *
     * @param commits     commit list
     * @param llmEnabled  whether LLM is enabled
     * @param moduleNames module name set
     * @return vague commit list
     */
    public List<CommitInfo> detect(List<CommitInfo> commits, boolean llmEnabled, Set<String> moduleNames) {
        if (commits.isEmpty()) {
            return List.of();
        }

        if (llmEnabled) {
            // LLM mode: temporarily use scoring engine, will replace after LLM layer implementation
            log.warn("LLM mode enabled but LlmVagueScanner not implemented, falling back to scoring engine");
            return detectWithScoringEngine(commits, moduleNames);
        }

        // Scoring engine mode
        return detectWithScoringEngine(commits, moduleNames);
    }

    /**
     * Detect vague commits using scoring engine.
     */
    private List<CommitInfo> detectWithScoringEngine(List<CommitInfo> commits, Set<String> moduleNames) {
        return scoringEngine.scoreAndFilter(commits, moduleNames);
    }
}
