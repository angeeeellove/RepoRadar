package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 易挥发文件检测规则：在指定时间窗口内修改次数超过阈值的文件标记为异常。
 * <p>
 * 使用滑动窗口算法，确保精确计算窗口内最大修改次数。
 *
 * @author frank
 */
public class VolatileFileRule {

    private final int windowDays;
    private final int threshold;

    public VolatileFileRule(int windowDays, int threshold) {
        this.windowDays = windowDays;
        this.threshold = threshold;
    }

    /**
     * 检查提交列表，返回在时间窗口内修改次数超过阈值的文件。
     *
     * @param commits 全量提交列表（按时间顺序）
     * @return 命中的易挥发文件列表
     */
    public List<VolatileFile> check(List<CommitInfo> commits) {
        Map<String, List<LocalDateTime>> fileTimes = new LinkedHashMap<>();
        Map<String, Set<String>> fileContributors = new LinkedHashMap<>();
        Map<String, Integer> fileLinesChanged = new LinkedHashMap<>();

        for (CommitInfo commit : commits) {
            LocalDateTime date = LocalDateTime.parse(commit.getDate());
            for (String file : commit.getChangedFiles()) {
                fileTimes.computeIfAbsent(file, k -> new ArrayList<>()).add(date);
                fileContributors.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(commit.getAuthor());
                fileLinesChanged.merge(file, commit.getTotalLines(), Integer::sum);
            }
        }

        List<VolatileFile> result = new ArrayList<>();
        for (Map.Entry<String, List<LocalDateTime>> entry : fileTimes.entrySet()) {
            String path = entry.getKey();
            List<LocalDateTime> times = entry.getValue();
            Collections.sort(times);

            int maxInWindow = computeMaxInWindow(times);
            if (maxInWindow > threshold) {
                result.add(new VolatileFile(path, maxInWindow, windowDays,
                        List.copyOf(fileContributors.get(path)),
                        fileLinesChanged.getOrDefault(path, 0)));
            }
        }
        return result;
    }

    private int computeMaxInWindow(List<LocalDateTime> times) {
        int maxInWindow = 0;
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime windowStart = times.get(i);
            long count = 0;
            for (LocalDateTime time : times) {
                if (!time.isBefore(windowStart) && ChronoUnit.DAYS.between(windowStart, time) <= windowDays) {
                    count++;
                }
            }
            maxInWindow = (int) Math.max(maxInWindow, count);
        }
        return maxInWindow;
    }

    /**
     * 易挥发文件数据对象。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolatileFile {
        /** 文件路径 */
        private String path;
        /** 窗口内最大修改次数 */
        private int changeCount;
        /** 窗口天数 */
        private int windowDays;
        /** 参与修改的贡献者 */
        private List<String> contributors;
        /** 总变动行数 */
        private int linesChanged;
    }
}
