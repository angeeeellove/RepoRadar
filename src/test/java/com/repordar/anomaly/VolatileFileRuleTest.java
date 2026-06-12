package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VolatileFileRule 单元测试。
 *
 * @author frank
 */
class VolatileFileRuleTest {

    private final VolatileFileRule rule = new VolatileFileRule(7, 3);

    @Test
    void shouldFlagFileModifiedMoreThanThresholdInWindow() {
        List<CommitInfo> commits = List.of(
                makeCommit("abc1", "2024-01-01T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc2", "2024-01-02T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc3", "2024-01-03T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc4", "2024-01-04T10:00:00", List.of("src/order/Service.java"))
        );
        var result = rule.check(commits);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getPath().contains("Service.java"));
    }

    @Test
    void shouldNotFlagFileWithExactThreshold() {
        List<CommitInfo> commits = List.of(
                makeCommit("abc1", "2024-01-01T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc2", "2024-01-02T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc3", "2024-01-03T10:00:00", List.of("src/order/Service.java"))
        );
        var result = rule.check(commits);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotFlagWhenModificationsSpreadBeyondWindow() {
        List<CommitInfo> commits = List.of(
                makeCommit("abc1", "2024-01-01T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc2", "2024-01-10T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc3", "2024-01-20T10:00:00", List.of("src/order/Service.java")),
                makeCommit("abc4", "2024-01-30T10:00:00", List.of("src/order/Service.java"))
        );
        var result = rule.check(commits);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldTrackMultipleFilesIndependently() {
        List<CommitInfo> commits = List.of(
                makeCommit("a1", "2024-01-01T10:00:00", List.of("src/order/A.java", "src/payment/B.java")),
                makeCommit("a2", "2024-01-02T10:00:00", List.of("src/order/A.java", "src/payment/B.java")),
                makeCommit("a3", "2024-01-03T10:00:00", List.of("src/order/A.java", "src/payment/B.java")),
                makeCommit("a4", "2024-01-04T10:00:00", List.of("src/order/A.java", "src/payment/B.java"))
        );
        var result = rule.check(commits);
        assertEquals(2, result.size());
    }

    private CommitInfo makeCommit(String hash, String date, List<String> files) {
        return new CommitInfo(hash, hash.substring(0, Math.min(4, hash.length())),
                "Alice", "a@b.com", date, "msg", 10, files.size(),
                files, Set.of("order"), 5, 5);
    }
}
