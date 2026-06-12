package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GiantCommitRule 单元测试。
 *
 * @author frank
 */
class GiantCommitRuleTest {

    private final GiantCommitRule rule = new GiantCommitRule(500);

    @Test
    void shouldFlagCommitWithMoreThan500TotalLines() {
        CommitInfo commit = makeCommit("abc", 600);
        List<CommitInfo> result = rule.check(List.of(commit));
        assertEquals(1, result.size());
        assertEquals("abc", result.get(0).getHash());
    }

    @Test
    void shouldNotFlagCommitWithExactThreshold() {
        CommitInfo commit = makeCommit("abc", 500);
        List<CommitInfo> result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotFlagSmallCommit() {
        CommitInfo commit = makeCommit("abc", 50);
        List<CommitInfo> result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldFilterFromMixedCommits() {
        List<CommitInfo> commits = List.of(
                makeCommit("small", 100),
                makeCommit("giant", 800),
                makeCommit("medium", 300),
                makeCommit("huge", 1200)
        );
        List<CommitInfo> result = rule.check(commits);
        assertEquals(2, result.size());
        assertEquals(List.of("giant", "huge"),
                result.stream().map(CommitInfo::getHash).toList());
    }

    @Test
    void shouldReturnEmptyForEmptyList() {
        assertTrue(rule.check(List.of()).isEmpty());
    }

    private CommitInfo makeCommit(String hash, int totalLines) {
        return new CommitInfo(hash, hash.substring(0, Math.min(4, hash.length())),
                "Author", "a@b.com", "2024-01-01", "msg", totalLines, 1,
                List.of("file.java"), Set.of("order"), totalLines / 2, totalLines / 2);
    }
}
