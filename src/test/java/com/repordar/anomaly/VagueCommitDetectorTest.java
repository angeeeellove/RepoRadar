package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VagueCommitDetector unit test.
 *
 * @author frank
 */
class VagueCommitDetectorTest {

    private VagueCommitDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VagueCommitDetector(new VagueScoringEngine(), null);
    }

    @Test
    void shouldDetectVagueCommitsWithScoringEngine() {
        // Given: commits with vague and clear messages
        CommitInfo vagueCommit = createCommit("hash1", "优化了代码");
        CommitInfo clearCommit = createCommit("hash2", "Fix null pointer exception in UserService");

        // When: detect with LLM disabled
        VagueCommitDetector.DetectResult result = detector.detect(
                List.of(vagueCommit, clearCommit), false, Set.of("test"),
                null, null, null);

        // Then: should detect vague commit ("优化了代码" matches VAGUE_PATTERNS, score < 50)
        assertTrue(result.getCommits().stream().anyMatch(c -> c.getHash().equals("hash1")));
        assertFalse(result.getCommits().stream().anyMatch(c -> c.getHash().equals("hash2")));
        // Scoring engine mode: reasons map should be empty
        assertTrue(result.getReasons().isEmpty());
    }

    @Test
    void shouldReturnEmptyListForEmptyCommits() {
        // Given: empty commit list
        // When: detect
        VagueCommitDetector.DetectResult result = detector.detect(List.of(), false, Set.of(),
                null, null, null);

        // Then: should return empty
        assertNotNull(result.getCommits());
        assertTrue(result.getCommits().isEmpty());
    }

    @Test
    void shouldFallbackToScoringEngineWhenLlmScannerIsNull() {
        // Given: a commit and LLM enabled but scanner is null
        CommitInfo commit = createCommit("hash1", "update");

        // When: detect with LLM enabled but no scanner
        VagueCommitDetector.DetectResult result = detector.detect(
                List.of(commit), true, Set.of("test"),
                "fake-key", "http://fake-url", "fake-model");

        // Then: should fallback to scoring engine
        assertNotNull(result.getCommits());
    }

    @Test
    void shouldFallbackToScoringEngineWhenApiKeyIsNull() {
        // Given: commits with null API key
        CommitInfo vagueCommit = createCommit("hash1", "优化了代码");

        // When: detect with LLM enabled but API key is null
        VagueCommitDetector.DetectResult result = detector.detect(
                List.of(vagueCommit), true, Set.of("test"),
                null, "http://fake-url", "fake-model");

        // Then: should fallback to scoring engine
        assertNotNull(result.getCommits());
        assertFalse(result.getCommits().isEmpty());
    }

    // Helper methods

    private CommitInfo createCommit(String hash, String message) {
        CommitInfo commit = new CommitInfo();
        commit.setHash(hash);
        commit.setShortHash(hash.substring(0, Math.min(7, hash.length())));
        commit.setAuthor("test");
        commit.setAuthorEmail("test@example.com");
        commit.setMessage(message);
        commit.setDate("2024-01-01T00:00:00");
        commit.setTotalLines(10);
        commit.setFilesChanged(1);
        commit.setLinesAdded(10);
        commit.setLinesDeleted(0);
        commit.setChangedFiles(List.of("file.txt"));
        commit.setModules(Set.of("test"));
        return commit;
    }
}
