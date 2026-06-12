package com.repordar.anomaly;

import com.repordar.git.CommitInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 跨域提交检测规则：单次提交涉及模块数超过阈值则标记为异常。
 *
 * @author frank
 */
public class CrossDomainRule {

    private final int threshold;

    public CrossDomainRule(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 检查提交列表，返回涉及模块数超过阈值的提交。
     *
     * @param commits 全量提交列表
     * @return 命中的跨域提交列表
     */
    public List<CommitInfo> check(List<CommitInfo> commits) {
        return commits.stream()
                .filter(c -> c.getModules().size() > threshold)
                .collect(Collectors.toList());
    }
}
