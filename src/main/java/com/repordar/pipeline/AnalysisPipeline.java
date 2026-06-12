package com.repordar.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.anomaly.AnomalyFilter;
import com.repordar.anomaly.VagueCommitDetector;
import com.repordar.anomaly.VolatileFileRule;
import com.repordar.config.AppProperties;
import com.repordar.dto.*;
import com.repordar.git.CommitInfo;
import com.repordar.git.GitCloner;
import com.repordar.git.MetadataExtractor;
import com.repordar.llm.LlmMapTranslator;
import com.repordar.llm.LlmReduceAnalyzer;
import com.repordar.report.ReportGenerator;
import com.repordar.sse.SseProgressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分析管线编排器，串联完整分析流程。
 * <p>
 * 流程：GitCloner → MetadataExtractor → 日期过滤 → AnomalyFilter
 * → VagueCommitDetector → LLM Map/Reduce → 统计构建 → ReportGenerator
 * <p>
 * 单线程同步管线，各阶段顺序执行。
 *
 * @author frank
 */
@Slf4j
@Component
public class AnalysisPipeline {

    private static final String VERSION = "1.0.0";
    private static final String DEFAULT_BRANCH = "HEAD";
    private static final DateTimeFormatter DATE_PARSER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter META_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final GitCloner gitCloner;
    private final MetadataExtractor metadataExtractor;
    private final AnomalyFilter anomalyFilter;
    private final VagueCommitDetector vagueCommitDetector;
    private final LlmMapTranslator llmMapTranslator;
    private final LlmReduceAnalyzer llmReduceAnalyzer;
    private final ReportGenerator reportGenerator;
    private final SseProgressService sseProgressService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * 构造分析管线（Spring 构造器注入）。
     */
    public AnalysisPipeline(GitCloner gitCloner,
                            MetadataExtractor metadataExtractor,
                            AnomalyFilter anomalyFilter,
                            VagueCommitDetector vagueCommitDetector,
                            LlmMapTranslator llmMapTranslator,
                            LlmReduceAnalyzer llmReduceAnalyzer,
                            ReportGenerator reportGenerator,
                            SseProgressService sseProgressService,
                            AppProperties appProperties,
                            ObjectMapper objectMapper) {
        this.gitCloner = gitCloner;
        this.metadataExtractor = metadataExtractor;
        this.anomalyFilter = anomalyFilter;
        this.vagueCommitDetector = vagueCommitDetector;
        this.llmMapTranslator = llmMapTranslator;
        this.llmReduceAnalyzer = llmReduceAnalyzer;
        this.reportGenerator = reportGenerator;
        this.sseProgressService = sseProgressService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行完整分析管线。
     *
     * @param repoRef  仓库 URL 或本地路径
     * @param outputDir 报告输出目录
     * @param since    分析起始日期（yyyy-MM-dd）
     * @param until    分析截止日期（yyyy-MM-dd）
     * @param branch   分析的分支（null 则使用 HEAD）
     * @param llmKey   LLM API Key
     * @param llmUrl   LLM API 基础 URL
     * @param llmModel LLM 模型名称
     * @return 生成的报告文件绝对路径
     * @throws Exception 分析过程中的异常
     */
    public String execute(String repoRef, String outputDir, String since, String until,
                          String branch, String llmKey, String llmUrl, String llmModel) throws Exception {
        long startTime = System.currentTimeMillis();
        boolean llmEnabled = llmKey != null && !llmKey.trim().isEmpty();

        log.info("开始分析管线: repo={}, branch={}, since={}, until={}, llm={}",
                repoRef, branch, since, until, llmEnabled);

        // 阶段 1: 克隆/打开仓库
        sseProgressService.sendProgress("clone", "正在克隆仓库...", 10);
        GitCloner.ClonedRepo clonedRepo = gitCloner.cloneOrOpen(repoRef);

        try {
            // 阶段 2: 提取元数据
            sseProgressService.sendProgress("metadata", "正在提取提交元数据...", 30);
            String ref = (branch != null && !branch.trim().isEmpty()) ? branch : DEFAULT_BRANCH;
            List<CommitInfo> allCommits = metadataExtractor.extract(clonedRepo.getRepository(), ref);

            if (allCommits.isEmpty()) {
                log.warn("仓库无提交记录");
                sseProgressService.sendProgress("empty", "仓库无提交记录", 100);
                return "";
            }

            // 阶段 3: 日期过滤
            sseProgressService.sendProgress("filter", "正在按日期过滤...", 40);
            List<CommitInfo> commits = filterByDate(allCommits, since, until);
            log.info("日期过滤: {} → {} 条提交", allCommits.size(), commits.size());

            if (commits.isEmpty()) {
                log.warn("日期范围内无提交记录");
                sseProgressService.sendProgress("empty", "日期范围内无提交记录", 100);
                return "";
            }

            // 收集所有模块名
            Set<String> allModules = commits.stream()
                    .flatMap(c -> c.getModules().stream())
                    .collect(Collectors.toSet());

            // 阶段 4: 异常检测
            sseProgressService.sendProgress("anomaly", "正在检测异常提交...", 50);
            AnomalyFilter.AnomalyResult anomalyResult = anomalyFilter.detect(commits);

            // 阶段 5: 模糊提交检测
            sseProgressService.sendProgress("vague", "正在检测模糊提交...", 60);
            List<CommitInfo> vagueCommits = vagueCommitDetector.detect(commits, llmEnabled, allModules);

            // 阶段 6: LLM 语义分析（如果启用）
            GlobalInsightDto globalInsight;
            if (llmEnabled) {
                sseProgressService.sendProgress("llm", "正在进行 LLM 语义分析...", 70);
                globalInsight = runLlmAnalysis(commits, llmKey, llmUrl, llmModel);
            } else {
                globalInsight = createDefaultInsight();
            }

            // 阶段 7: 构建统计数据并组装报告
            sseProgressService.sendProgress("report", "正在生成报告...", 85);
            long durationMs = System.currentTimeMillis() - startTime;

            String repoName = extractRepoName(repoRef);
            ReportDataDto reportData = assembleReportData(
                    repoName, repoRef, ref, since, until, commits,
                    anomalyResult, vagueCommits, globalInsight,
                    llmEnabled, llmModel, durationMs
            );

            // 阶段 8: 生成报告文件
            String templateHtml = loadTemplate();
            String reportPath = generateReport(reportData, outputDir, templateHtml);

            sseProgressService.sendComplete(reportPath);
            log.info("分析完成: 耗时 {}ms, 报告: {}", durationMs, reportPath);
            return reportPath;

        } finally {
            gitCloner.cleanup(clonedRepo);
        }
    }

    /**
     * 生成报告 HTML 文件（供测试直接调用）。
     *
     * @param data     报告数据
     * @param outputDir 输出目录
     * @param templateHtml HTML 模板内容
     * @return 报告文件绝对路径
     * @throws IOException IO 异常
     */
    public String generateReport(ReportDataDto data, String outputDir, String templateHtml) throws IOException {
        return reportGenerator.generate(data, outputDir, templateHtml);
    }

    // ==================== 日期过滤 ====================

    /**
     * 按日期范围过滤提交。
     *
     * @param commits 原始提交列表
     * @param since   起始日期（yyyy-MM-dd），null 表示不限
     * @param until   截止日期（yyyy-MM-dd），null 表示不限
     * @return 过滤后的提交列表
     */
    List<CommitInfo> filterByDate(List<CommitInfo> commits, String since, String until) {
        if (commits == null || commits.isEmpty()) {
            return Collections.emptyList();
        }
        if (since == null && until == null) {
            return commits;
        }

        String sinceDateTime = (since != null) ? since + "T00:00:00" : null;
        String untilDateTime = (until != null) ? until + "T23:59:59" : null;

        return commits.stream()
                .filter(c -> {
                    String date = c.getDate();
                    if (date == null) {
                        return false;
                    }
                    if (sinceDateTime != null && date.compareTo(sinceDateTime) < 0) {
                        return false;
                    }
                    if (untilDateTime != null && date.compareTo(untilDateTime) > 0) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    // ==================== 作者统计 ====================

    /**
     * 构建作者统计数据。
     *
     * @param commits 提交列表
     * @return 作者统计列表（按提交数降序）
     */
    List<AuthorStatsDto> buildAuthorStats(List<CommitInfo> commits) {
        if (commits == null || commits.isEmpty()) {
            return Collections.emptyList();
        }

        // 按 email 分组统计
        Map<String, List<CommitInfo>> byEmail = commits.stream()
                .collect(Collectors.groupingBy(CommitInfo::getAuthorEmail, LinkedHashMap::new, Collectors.toList()));

        List<AuthorStatsDto> result = new ArrayList<>(byEmail.size());
        for (Map.Entry<String, List<CommitInfo>> entry : byEmail.entrySet()) {
            List<CommitInfo> authorCommits = entry.getValue();
            CommitInfo first = authorCommits.get(0);

            String name = first.getAuthor();
            String email = entry.getKey();
            int commitCount = authorCommits.size();
            int linesAdded = authorCommits.stream().mapToInt(CommitInfo::getLinesAdded).sum();
            int linesDeleted = authorCommits.stream().mapToInt(CommitInfo::getLinesDeleted).sum();

            // 活跃时段分布（24小时）
            int[] hourDist = buildHourDistribution(authorCommits);
            // 活跃星期分布（7天）
            int[] dayDist = buildDayDistribution(authorCommits);
            // 活跃高峰时段
            String peakHours = computePeakHours(hourDist);
            // 主要贡献模块
            List<String> primaryModules = computePrimaryModules(authorCommits);
            // 活跃画像
            String profile = computeProfile(hourDist);

            result.add(new AuthorStatsDto(name, email, commitCount, linesAdded, linesDeleted,
                    hourDist, dayDist, peakHours, primaryModules, profile));
        }

        // 按提交数降序排列
        result.sort((a, b) -> Integer.compare(b.getCommitCount(), a.getCommitCount()));
        return result;
    }

    /**
     * 构建小时活跃分布（24小时）。
     */
    private int[] buildHourDistribution(List<CommitInfo> commits) {
        int[] dist = new int[24];
        for (CommitInfo c : commits) {
            try {
                LocalDateTime dt = LocalDateTime.parse(c.getDate(), DATE_PARSER);
                dist[dt.getHour()]++;
            } catch (Exception e) {
                // 日期解析失败，跳过
            }
        }
        return dist;
    }

    /**
     * 构建星期活跃分布（7天，周一=0 到 周日=6）。
     */
    private int[] buildDayDistribution(List<CommitInfo> commits) {
        int[] dist = new int[7];
        for (CommitInfo c : commits) {
            try {
                LocalDateTime dt = LocalDateTime.parse(c.getDate(), DATE_PARSER);
                int dayOfWeek = dt.getDayOfWeek().getValue() - 1; // Monday=0
                dist[dayOfWeek]++;
            } catch (Exception e) {
                // 日期解析失败，跳过
            }
        }
        return dist;
    }

    /**
     * 计算活跃高峰时段。
     */
    private String computePeakHours(int[] hourDist) {
        int maxIdx = 0;
        int maxVal = 0;
        for (int i = 0; i < hourDist.length; i++) {
            if (hourDist[i] > maxVal) {
                maxVal = hourDist[i];
                maxIdx = i;
            }
        }
        return maxIdx + ":00-" + ((maxIdx + 1) % 24) + ":00";
    }

    /**
     * 计算主要贡献模块（取前 3 个）。
     */
    private List<String> computePrimaryModules(List<CommitInfo> commits) {
        Map<String, Long> moduleCounts = commits.stream()
                .flatMap(c -> c.getModules().stream())
                .collect(Collectors.groupingBy(m -> m, Collectors.counting()));

        return moduleCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 根据活跃时段生成画像标签。
     */
    private String computeProfile(int[] hourDist) {
        int maxIdx = 0;
        int maxVal = 0;
        for (int i = 0; i < hourDist.length; i++) {
            if (hourDist[i] > maxVal) {
                maxVal = hourDist[i];
                maxIdx = i;
            }
        }
        if (maxIdx >= 6 && maxIdx < 12) {
            return "早鸟型";
        } else if (maxIdx >= 12 && maxIdx < 18) {
            return "稳定型";
        } else if (maxIdx >= 18 && maxIdx < 22) {
            return "夜猫型";
        } else {
            return "夜猫型";
        }
    }

    // ==================== 模块统计 ====================

    /**
     * 构建模块统计数据。
     *
     * @param commits 提交列表
     * @return 模块统计列表（按变更行数降序）
     */
    List<ModuleStatsDto> buildModuleStats(List<CommitInfo> commits) {
        if (commits == null || commits.isEmpty()) {
            return Collections.emptyList();
        }

        // 按模块分组统计
        Map<String, List<CommitInfo>> byModule = new LinkedHashMap<>();
        for (CommitInfo c : commits) {
            for (String module : c.getModules()) {
                byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(c);
            }
        }

        List<ModuleStatsDto> result = new ArrayList<>(byModule.size());
        for (Map.Entry<String, List<CommitInfo>> entry : byModule.entrySet()) {
            String moduleName = entry.getKey();
            List<CommitInfo> moduleCommits = entry.getValue();

            int commitCount = moduleCommits.size();
            int linesChanged = moduleCommits.stream().mapToInt(CommitInfo::getTotalLines).sum();

            // 贡献者排名
            Map<String, Long> contributorCounts = moduleCommits.stream()
                    .collect(Collectors.groupingBy(CommitInfo::getAuthor, Collectors.counting()));
            List<String> topContributors = contributorCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            // 模块洞察（简单文本）
            String insight = moduleName + ": " + commitCount + " 次提交, " + linesChanged + " 行变更";

            result.add(new ModuleStatsDto(moduleName, commitCount, linesChanged, topContributors, insight));
        }

        // 按变更行数降序排列
        result.sort((a, b) -> Integer.compare(b.getLinesChanged(), a.getLinesChanged()));
        return result;
    }

    // ==================== 活跃热力图 ====================

    /**
     * 构建活跃热力图数据。
     *
     * @param commits 提交列表
     * @return 热力图数据
     */
    ActivityHeatmapDto buildActivityHeatmap(List<CommitInfo> commits) {
        if (commits == null || commits.isEmpty()) {
            return new ActivityHeatmapDto(Collections.emptyList(), 0);
        }

        // key = "dayOfWeek-hour", value = count
        Map<String, Integer> heatmapMap = new LinkedHashMap<>();
        for (CommitInfo c : commits) {
            try {
                LocalDateTime dt = LocalDateTime.parse(c.getDate(), DATE_PARSER);
                int dayOfWeek = dt.getDayOfWeek().getValue() - 1; // Monday=0
                int hour = dt.getHour();
                String key = dayOfWeek + "-" + hour;
                heatmapMap.merge(key, 1, Integer::sum);
            } catch (Exception e) {
                // 日期解析失败，跳过
            }
        }

        int maxCount = 0;
        List<HeatmapPoint> points = new ArrayList<>(heatmapMap.size());
        for (Map.Entry<String, Integer> entry : heatmapMap.entrySet()) {
            String[] parts = entry.getKey().split("-");
            int day = Integer.parseInt(parts[0]);
            int hour = Integer.parseInt(parts[1]);
            int count = entry.getValue();
            points.add(new HeatmapPoint(day, hour, count));
            if (count > maxCount) {
                maxCount = count;
            }
        }

        return new ActivityHeatmapDto(points, maxCount);
    }

    // ==================== 异常分组 DTO ====================

    /**
     * 构建异常分组 DTO。
     *
     * @param anomalyResult 异常检测结果
     * @param vagueCommits  模糊提交列表
     * @return 异常分组 DTO
     */
    AnomalyGroupDto buildAnomalyGroup(AnomalyFilter.AnomalyResult anomalyResult,
                                       List<CommitInfo> vagueCommits) {
        // 巨型提交
        List<GiantCommitDto> giantCommits = anomalyResult.getGiantCommits().stream()
                .map(this::toGiantCommitDto)
                .collect(Collectors.toList());

        // 易挥发文件
        List<VolatileFileDto> volatileFiles = anomalyResult.getVolatileFiles().stream()
                .map(this::toVolatileFileDto)
                .collect(Collectors.toList());

        // 跨域提交
        List<CrossDomainCommitDto> crossDomainCommits = anomalyResult.getCrossDomainCommits().stream()
                .map(this::toCrossDomainCommitDto)
                .collect(Collectors.toList());

        // 模糊提交
        List<VagueCommitDto> vagueCommitDtos = vagueCommits.stream()
                .map(this::toVagueCommitDto)
                .collect(Collectors.toList());

        return new AnomalyGroupDto(giantCommits, volatileFiles, crossDomainCommits, vagueCommitDtos);
    }

    private GiantCommitDto toGiantCommitDto(CommitInfo c) {
        return new GiantCommitDto(
                c.getHash(), c.getShortHash(), c.getAuthor(), c.getAuthorEmail(),
                c.getDate(), c.getMessage(), c.getTotalLines(), c.getFilesChanged(),
                new ArrayList<>(c.getModules()), null
        );
    }

    private VolatileFileDto toVolatileFileDto(VolatileFileRule.VolatileFile vf) {
        return new VolatileFileDto(
                vf.getPath(), vf.getChangeCount(), vf.getWindowDays(),
                vf.getContributors(), vf.getLinesChanged()
        );
    }

    private CrossDomainCommitDto toCrossDomainCommitDto(CommitInfo c) {
        return new CrossDomainCommitDto(
                c.getHash(), c.getShortHash(), c.getAuthor(), c.getAuthorEmail(),
                c.getDate(), c.getMessage(), new ArrayList<>(c.getModules()),
                c.getFilesChanged(), null
        );
    }

    private VagueCommitDto toVagueCommitDto(CommitInfo c) {
        com.repordar.anomaly.VagueScoringEngine engine = new com.repordar.anomaly.VagueScoringEngine();
        int score = engine.score(c.getMessage());
        String reason = engine.generateReason(c.getMessage(), score);
        return new VagueCommitDto(
                c.getHash(), c.getShortHash(), c.getAuthor(), c.getAuthorEmail(),
                c.getDate(), c.getMessage(), reason, score, null
        );
    }

    // ==================== 报告数据组装 ====================

    /**
     * 组装完整报告数据。
     */
    ReportDataDto assembleReportData(String repoName, String repoUrl, String branch,
                                      String since, String until,
                                      List<CommitInfo> commits,
                                      AnomalyFilter.AnomalyResult anomalyResult,
                                      List<CommitInfo> vagueCommits,
                                      GlobalInsightDto globalInsight,
                                      boolean llmEnabled, String llmModel,
                                      long durationMs) {
        // 仓库信息
        Set<String> allFiles = commits.stream()
                .flatMap(c -> c.getChangedFiles().stream())
                .collect(Collectors.toSet());

        RepositoryInfoDto repoInfo = new RepositoryInfoDto(
                repoName, repoUrl, branch,
                new RepositoryInfoDto.AnalysisPeriod(since, until),
                commits.size(),
                (int) commits.stream().map(CommitInfo::getAuthorEmail).distinct().count(),
                allFiles.size()
        );

        // 统计数据
        List<AuthorStatsDto> authorStats = buildAuthorStats(commits);
        List<ModuleStatsDto> moduleStats = buildModuleStats(commits);
        AnomalyGroupDto anomalyGroup = buildAnomalyGroup(anomalyResult, vagueCommits);
        ActivityHeatmapDto heatmap = buildActivityHeatmap(commits);

        // 元信息
        AnalysisMetaDto meta = new AnalysisMetaDto(
                LocalDateTime.now().format(META_TIMESTAMP),
                llmEnabled,
                llmModel != null ? llmModel : "",
                durationMs,
                VERSION
        );

        return new ReportDataDto(repoInfo, authorStats, moduleStats, anomalyGroup,
                globalInsight, heatmap, meta);
    }

    // ==================== LLM 分析 ====================

    /**
     * 运行 LLM Map/Reduce 分析。
     * LLM 调用失败时优雅降级。
     */
    private GlobalInsightDto runLlmAnalysis(List<CommitInfo> commits,
                                             String llmKey, String llmUrl, String llmModel) {
        try {
            // Reduce 阶段：生成全局洞察
            List<AuthorStatsDto> authorStats = buildAuthorStats(commits);
            List<ModuleStatsDto> moduleStats = buildModuleStats(commits);
            return llmReduceAnalyzer.generateInsight(authorStats, moduleStats, llmUrl, llmKey, llmModel);
        } catch (Exception e) {
            log.warn("LLM 分析失败，降级为默认洞察: {}", e.getMessage());
            return createDefaultInsight();
        }
    }

    /**
     * 创建默认洞察（LLM 未启用或失败时）。
     */
    private GlobalInsightDto createDefaultInsight() {
        return new GlobalInsightDto(
                "纯统计分析模式，未启用 LLM 深度洞察",
                Collections.emptyList(),
                0
        );
    }

    // ==================== 工具方法 ====================

    /**
     * 从仓库 URL/路径提取仓库名。
     */
    private String extractRepoName(String repoRef) {
        if (repoRef == null) {
            return "unknown";
        }
        // 处理 URL: https://github.com/org/repo.git → repo
        if (repoRef.startsWith("http://") || repoRef.startsWith("https://") || repoRef.startsWith("git@")) {
            String name = repoRef;
            if (name.endsWith(".git")) {
                name = name.substring(0, name.length() - 4);
            }
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < name.length() - 1) {
                return name.substring(lastSlash + 1);
            }
        }
        // 处理本地路径: /path/to/repo → repo
        Path path = Path.of(repoRef);
        Path fileName = path.getFileName();
        return (fileName != null) ? fileName.toString() : "unknown";
    }

    /**
     * 加载 HTML 报告模板。
     */
    private String loadTemplate() throws IOException {
        // 尝试从 classpath 加载
        try {
            var inputStream = getClass().getClassLoader()
                    .getResourceAsStream("report-template/index.html");
            if (inputStream != null) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("从 classpath 加载模板失败: {}", e.getMessage());
        }

        // 兜底：最小模板
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<title>RepoRadar Report</title></head>"
                + "<body><script>const DATA = __INJECT_DATA__;</script>"
                + "<div id=\"app\"><p>Report Data Loaded.</p></div></body></html>";
    }
}
