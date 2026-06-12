package com.repordar.anomaly;

import com.repordar.git.CommitInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模糊提交评分引擎（降级模式）。
 * <p>
 * 纯 Java 特征评分，零外部依赖。满分 100，低于 50 判定模糊。
 * <ul>
 *   <li>减分：泛指词(-30)、缺少具体内容(-25)、描述过短(-20)、已知模糊模式(-25)</li>
 *   <li>加分：引用模块名(+15)、引用 Issue 编号(+10)、引用错误码(+10)</li>
 * </ul>
 * 支持 Conventional Commits 格式解析，仅分析 description 部分。
 *
 * @author frank
 */
@Component
public class VagueScoringEngine {

    private static final int VAGUE_THRESHOLD = 50;
    private static final int MIN_DESCRIPTION_LENGTH = 5;

    private static final Set<String> GENERIC_PHRASES = Set.of(
            "相关BUG", "相关的BUG", "的BUG", "一些修改", "若干调整", "部分优化", "相关问题",
            "相关功能", "相关的功能", "代码优化", "代码调整", "小修改", "小改动"
    );
    private static final Set<String> VAGUE_PATTERNS = Set.of(
            "优化了代码", "修复问题", "小改动", "调整代码", "优化", "调整", "修复"
    );

    /** 英文单单词模糊提交（极其模糊，只有动词没有宾语） */
    private static final Set<String> SINGLE_WORD_VAGUE_EN = Set.of(
            "update", "fix", "wip", "temp", "done", "minor", "changes", "cleanup",
            "misc", "other", "test", "debug", "refactor", "change", "modify"
    );
    private static final Pattern ISSUE_REF = Pattern.compile("#\\d+|[A-Z]+-\\d+");
    private static final Pattern ERROR_CODE = Pattern.compile(
            "[A-Z][a-z]*Exception|OOM|NPE|ERR[-_]?\\d+", Pattern.CASE_INSENSITIVE
    );

    private List<String> repoModuleNames;

    /**
     * 默认构造器（Spring 注入使用）。
     */
    public VagueScoringEngine() {
        this.repoModuleNames = List.of();
    }

    /**
     * 带模块名列表的构造器（测试使用）。
     *
     * @param repoModuleNames 仓库模块名列表
     */
    public VagueScoringEngine(List<String> repoModuleNames) {
        this.repoModuleNames = repoModuleNames != null ? repoModuleNames : List.of();
    }

    /**
     * 对提交列表进行评分过滤，返回模糊提交（评分低于阈值）。
     *
     * @param commits     提交列表
     * @param moduleNames 模块名集合（优先于构造器中的列表）
     * @return 模糊提交列表
     */
    public List<CommitInfo> scoreAndFilter(List<CommitInfo> commits, Set<String> moduleNames) {
        List<String> effectiveModules = moduleNames != null
                ? moduleNames.stream().toList() : repoModuleNames;
        List<CommitInfo> result = new ArrayList<>();
        for (CommitInfo commit : commits) {
            int s = score(commit.getMessage(), effectiveModules);
            if (s < VAGUE_THRESHOLD) {
                result.add(commit);
            }
        }
        return result;
    }

    /**
     * 对提交信息进行模糊度评分。
     *
     * @param message 提交信息
     * @return 评分（0-100），低于 50 为模糊
     */
    public int score(String message) {
        return score(message, repoModuleNames);
    }

    /**
     * 对提交信息进行模糊度评分（带动态模块名）。
     *
     * @param message     提交信息
     * @param moduleNames 动态传入的模块名列表
     * @return 评分（0-100），低于 50 为模糊
     */
    public int score(String message, List<String> moduleNames) {
        if (message == null || message.isBlank()) {
            return 0;
        }

        int score = 100;
        String description = message;

        // 解析 Conventional Commits 格式（type[(scope)]: description）
        description = extractDescription(message);
        if (!description.equals(message.trim()) && description.isEmpty()) {
            return Math.max(0, score - 60);
        }

        // 减分项：泛指词（可叠加）
        for (String phrase : GENERIC_PHRASES) {
            if (description.contains(phrase)) {
                score -= 30;
            }
        }

        // 减分项：缺少具体内容（只有动词没有名词）
        boolean hasSpecificContent = description.length() > MIN_DESCRIPTION_LENGTH
                && description.chars().filter(Character::isLetterOrDigit).count() > 3;
        if (!hasSpecificContent) {
            score -= 25;
        }

        // 减分项：description 太短
        if (description.length() <= MIN_DESCRIPTION_LENGTH) {
            score -= 20;
        }

        // 减分项：匹配已知模糊模式
        for (String pattern : VAGUE_PATTERNS) {
            if (description.equals(pattern) || message.trim().equals(pattern)) {
                score -= 25;
                break;
            }
        }

        // 减分项：英文单单词提交（只有动词没有宾语，极其模糊）
        String lowerDesc = description.toLowerCase();
        if (SINGLE_WORD_VAGUE_EN.contains(lowerDesc)) {
            score -= 55;
        }

        // 加分项：引用具体模块名（复用上面的 lowerDesc）
        for (String mod : repoModuleNames) {
            if (lowerDesc.contains(mod.toLowerCase())) {
                score += 15;
                break;
            }
        }

        // 加分项：Issue 编号
        if (ISSUE_REF.matcher(description).find()) {
            score += 10;
        }

        // 加分项：错误码/异常名
        if (ERROR_CODE.matcher(description).find()) {
            score += 10;
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 根据评分生成模糊原因描述。
     *
     * @param message 提交信息
     * @param score    评分
     * @return 模糊原因描述
     */
    public String generateReason(String message, int score) {
        if (score >= VAGUE_THRESHOLD) {
            return "提交信息基本清晰";
        }

        StringBuilder reason = new StringBuilder();
        String desc = extractDescription(message);

        for (String phrase : GENERIC_PHRASES) {
            if (desc.contains(phrase)) {
                reason.append("包含泛指词\"").append(phrase).append("\"；");
            }
        }

        if (desc.length() <= MIN_DESCRIPTION_LENGTH) {
            reason.append("描述过短；");
        }

        for (String pattern : VAGUE_PATTERNS) {
            if (desc.equals(pattern)) {
                reason.append("匹配已知模糊模式\"").append(pattern).append("\"；");
            }
        }

        if (SINGLE_WORD_VAGUE_EN.contains(desc.toLowerCase())) {
            reason.append("英文单单词提交，缺少具体描述；");
        }

        if (reason.isEmpty()) {
            reason.append("缺少具体描述");
        }

        return reason.toString();
    }

    /**
     * 从提交信息中提取 description 部分。
     * 支持 Conventional Commits 格式：type[(scope)]: description
     * 若不匹配 CC 格式，返回原始 message。
     */
    private String extractDescription(String message) {
        String trimmed = message.trim();
        int colonIndex = trimmed.indexOf(':');
        if (colonIndex < 0) {
            return trimmed;
        }
        String prefix = trimmed.substring(0, colonIndex);
        // 校验 prefix 是否为合法的 type 或 type(scope)
        if (isConventionalPrefix(prefix)) {
            String desc = trimmed.substring(colonIndex + 1).trim();
            return desc;
        }
        return trimmed;
    }

    /**
     * 校验前缀是否为 Conventional Commits 格式：type 或 type(scope)。
     * type 只包含字母数字下划线，scope 额外允许连字符。
     */
    private boolean isConventionalPrefix(String prefix) {
        int openParen = prefix.indexOf('(');
        if (openParen < 0) {
            // 纯 type: word characters only
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
}
