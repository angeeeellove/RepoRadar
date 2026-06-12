package com.repordar.anomaly;

import com.repordar.git.CommitInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 巨型提交检测规则：总变动行数超过阈值则标记为异常。
 *
 * @author frank
 */
public class GiantCommitRule {

    private final int threshold;

    public GiantCommitRule(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 检查提交列表，返回总变动行数超过阈值的提交。
     *
     * @param commits 全量提交列表
     * @return 命中的巨型提交列表
     */
    public List<CommitInfo> check(List<CommitInfo> commits) {
        return commits.stream()
                .filter(c -> c.getTotalLines() > threshold)
                .collect(Collectors.toList());
    }
}
