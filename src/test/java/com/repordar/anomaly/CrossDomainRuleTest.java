package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CrossDomainRule 单元测试。
 *
 * @author frank
 */
class CrossDomainRuleTest {

    private final CrossDomainRule rule = new CrossDomainRule(3);

    @Test
    void shouldFlagCommitWithMoreThan3Modules() {
        CommitInfo commit = makeCommit("abc", Set.of("order", "payment", "user", "inventory"));
        List<CommitInfo> result = rule.check(List.of(commit));
        assertEquals(1, result.size());
    }

    @Test
    void shouldNotFlagCommitWith3OrFewerModules() {
        CommitInfo commit = makeCommit("abc", Set.of("order", "payment", "user"));
        List<CommitInfo> result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotFlagSingleModuleCommit() {
        CommitInfo commit = makeCommit("abc", Set.of("order"));
        List<CommitInfo> result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptyList() {
        assertTrue(rule.check(List.of()).isEmpty());
    }

    private CommitInfo makeCommit(String hash, Set<String> modules) {
        return new CommitInfo(hash, hash.substring(0, Math.min(4, hash.length())),
                "Alice", "a@b.com", "2024-01-01", "msg", 100, 5,
                List.of("a.java"), modules, 50, 50);
    }
}
