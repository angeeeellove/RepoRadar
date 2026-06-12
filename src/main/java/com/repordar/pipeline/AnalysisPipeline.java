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

    /** 活跃时段阈值：早晨开始小时 */
    private static final int HOUR_MORNING_START = 6;
    /** 活跃时段阈值：早晨结束 / 下午开始小时 */
    private static final int HOUR_AFTERNOON_START = 12;
    /** 活跃时段阈值：下午结束 / 晚上开始小时 */
    private static final int HOUR_EVENING_START = 18;
    /** 活跃时段阈值：晚上结束小时 */
    private static final int HOUR_NIGHT_START = 22;
    /** Git URL 后缀 */
    private static final String GIT_URL_SUFFIX = ".git";
    /** 远程 URL 协议前缀 */
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";
    private static final String GIT_SSH_PREFIX = "git@";

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
        sseProgressService.sendProgress("CLONE", "正在克隆仓库...", 10);
        GitCloner.ClonedRepo clonedRepo = gitCloner.cloneOrOpen(repoRef);

        try {
            // 阶段 2: 提取元数据
            sseProgressService.sendProgress("METADATA", "正在提取提交元数据...", 30);
            String ref = (branch != null && !branch.trim().isEmpty()) ? branch : DEFAULT_BRANCH;
            List<CommitInfo> allCommits = metadataExtractor.extract(clonedRepo.getRepository(), ref);

            if (allCommits.isEmpty()) {
                log.warn("仓库无提交记录");
                sseProgressService.sendProgress("empty", "仓库无提交记录", 100);
                return "";
            }

            // 阶段 3: 日期过滤
            sseProgressService.sendProgress("FILTER", "正在按日期过滤...", 40);
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
            sseProgressService.sendProgress("ANOMALY", "正在检测异常提交...", 50);
            AnomalyFilter.AnomalyResult anomalyResult = anomalyFilter.detect(commits);

            // 阶段 5: 模糊提交检测
            sseProgressService.sendProgress("LLM_VAGUE", "正在检测模糊提交...", 55);
            VagueCommitDetector.DetectResult vagueResult =
                    vagueCommitDetector.detect(commits, llmEnabled, allModules, llmKey, llmUrl, llmModel);
            List<CommitInfo> vagueCommits = vagueResult.getCommits();
            Map<String, String> vagueReasons = vagueResult.getReasons();

            // 阶段 6: LLM 语义分析（如果启用）
            GlobalInsightDto globalInsight;
            Map<String, CommitAnalysisDto> analysisMap;
            if (llmEnabled) {
                // Map 阶段：逐提交语义分析
                analysisMap = runLlmMapPhase(anomalyResult, vagueCommits, llmKey, llmUrl, llmModel);
                // 自校验：移除 Map 分析质量为 EXCELLENT/HIGH 的误判模糊提交
                vagueCommits = filterFalsePositiveVague(vagueCommits, vagueReasons, analysisMap);
                // Reduce 阶段：生成全局洞察
                sseProgressService.sendProgress("LLM_REDUCE", "生成全局洞察...", 80);
                globalInsight = runLlmReduce(commits, analysisMap, llmKey, llmUrl, llmModel);
            } else {
                globalInsight = createDefaultInsight();
                analysisMap = Map.of();
            }

            // 阶段 7: 构建统计数据并组装报告
            sseProgressService.sendProgress("REPORT", "正在生成报告...", 85);
            long durationMs = System.currentTimeMillis() - startTime;

            String repoName = extractRepoName(repoRef);
            ReportDataDto reportData = assembleReportData(
                    repoName, repoRef, ref, since, until, commits,
                    anomalyResult, vagueCommits, vagueReasons, analysisMap, globalInsight,
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
                // Monday=0, Sunday=6
                int dayOfWeek = dt.getDayOfWeek().getValue() - 1;
                dist[dayOfWeek]++;
            } catch (Exception e) {
                log.debug("星期分布统计跳过日期解析失败: {}", c.getDate());
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
        if (maxIdx >= HOUR_MORNING_START && maxIdx < HOUR_AFTERNOON_START) {
            return "早鸟型";
        } else if (maxIdx >= HOUR_AFTERNOON_START && maxIdx < HOUR_EVENING_START) {
            return "稳定型";
        } else if (maxIdx >= HOUR_EVENING_START && maxIdx < HOUR_NIGHT_START) {
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
                // Monday=0, Sunday=6
                int dayOfWeek = dt.getDayOfWeek().getValue() - 1;
                int hour = dt.getHour();
                String key = dayOfWeek + "-" + hour;
                heatmapMap.merge(key, 1, Integer::sum);
            } catch (Exception e) {
                log.debug("热力图统计跳过日期解析失败: {}", c.getDate());
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
     * @param analysisMap   LLM 分析结果（key=commit hash），可为空 map
     * @param llmEnabled    是否启用 LLM（影响模糊提交标准描述）
     * @return 异常分组 DTO
     */
    AnomalyGroupDto buildAnomalyGroup(AnomalyFilter.AnomalyResult anomalyResult,
                                       List<CommitInfo> vagueCommits,
                                       Map<String, String> vagueReasons,
                                       Map<String, CommitAnalysisDto> analysisMap,
                                       boolean llmEnabled) {
        Map<String, CommitAnalysisDto> safeMap = analysisMap != null ? analysisMap : Map.of();
        Map<String, String> safeReasons = vagueReasons != null ? vagueReasons : Map.of();

        // 巨型提交
        List<GiantCommitDto> giantCommits = anomalyResult.getGiantCommits().stream()
                .map(c -> toGiantCommitDto(c, safeMap.get(c.getHash())))
                .collect(Collectors.toList());

        // 易挥发文件
        List<VolatileFileDto> volatileFiles = anomalyResult.getVolatileFiles().stream()
                .map(this::toVolatileFileDto)
                .collect(Collectors.toList());

        // 跨域提交
        List<CrossDomainCommitDto> crossDomainCommits = anomalyResult.getCrossDomainCommits().stream()
                .map(c -> toCrossDomainCommitDto(c, safeMap.get(c.getHash())))
                .collect(Collectors.toList());

        // 模糊提交（优先使用 LLM 原因，否则用规则引擎）
        List<VagueCommitDto> vagueCommitDtos = vagueCommits.stream()
                .map(c -> toVagueCommitDto(c, safeReasons.get(c.getShortHash()), safeMap.get(c.getHash())))
                .collect(Collectors.toList());

        // 检测标准说明
        AppProperties.Anomaly anomaly = appProperties.getAnomaly();
        String giantCriteria = "单次提交总变动行数超过 " + anomaly.getGiantCommitThreshold() + " 行";
        String volatileCriteria = anomaly.getVolatileWindowDays() + " 天内修改超过 "
                + anomaly.getVolatileThreshold() + " 次的文件";
        String crossCriteria = "单次提交涉及模块数超过 " + anomaly.getCrossDomainThreshold() + " 个";
        String vagueCriteria = llmEnabled
                ? "由 AI 语义分析判断，标准：提交信息须说明涉及的功能模块或修复的问题"
                : "评分低于 50 分（满分 100），基于特征匹配：泛指词、描述过短、缺少具体内容";

        return new AnomalyGroupDto(giantCommits, volatileFiles, crossDomainCommits, vagueCommitDtos,
                giantCriteria, volatileCriteria, crossCriteria, vagueCriteria);
    }

    private GiantCommitDto toGiantCommitDto(CommitInfo c, CommitAnalysisDto analysis) {
        return new GiantCommitDto(
                c.getHash(), c.getShortHash(), c.getAuthor(), c.getAuthorEmail(),
                c.getDate(), c.getMessage(), c.getTotalLines(), c.getFilesChanged(),
                new ArrayList<>(c.getModules()), analysis
        );
    }

    private VolatileFileDto toVolatileFileDto(VolatileFileRule.VolatileFile vf) {
        return new VolatileFileDto(
                vf.getPath(), vf.getChangeCount(), vf.getWindowDays(),
                vf.getContributors(), vf.getLinesChanged()
        );
    }

    private CrossDomainCommitDto toCrossDomainCommitDto(CommitInfo c, CommitAnalysisDto analysis) {
        return new CrossDomainCommitDto(
                c.getHash(), c.getShortHash(), c.getAuthor(), c.getAuthorEmail(),
                c.getDate(), c.getMessage(), new ArrayList<>(c.getModules()),
                c.getFilesChanged(), analysis
        );
    }

    /**
     * 转换模糊提交 DTO。
     * 优先使用 LLM 的原因描述，无 LLM 结果时降级为规则评分。
     *
     * @param c         提交信息
     * @param llmReason LLM 模糊原因（可为 null）
     * @param analysis  LLM 语义分析结果（可为 null）
     */
    private VagueCommitDto toVagueCommitDto(CommitInfo c, String llmReason, CommitAnalysisDto analysis) {
        String reason;
        int score;
        if (llmReason != null && !llmReason.isBlank()) {
            // LLM 识别的模糊提交：使用 LLM 原因，标记为 LLM 检测（score = -1）
            reason = "[LLM] " + llmReason;
            score = -1;
        } else {
            // 规则引擎检测：使用评分引擎
            com.repordar.anomaly.VagueScoringEngine engine = new com.repordar.anomaly.VagueScoringEngine();
            score = engine.score(c.getMessage());
            reason = engine.generateReason(c.getMessage(), score);
        }
        return new VagueCommitDto(
                c.getHash(), c.getShortHash(), c.getAuthor(), c.getAuthorEmail(),
                c.getDate(), c.getMessage(), reason, score, analysis
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
                                      Map<String, String> vagueReasons,
                                      Map<String, CommitAnalysisDto> analysisMap,
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
        AnomalyGroupDto anomalyGroup = buildAnomalyGroup(anomalyResult, vagueCommits, vagueReasons, analysisMap, llmEnabled);
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

    /** Map 分析判定为高质量的标记，用于自校验过滤 */
    private static final Set<String> HIGH_QUALITY_MARKS = Set.of("EXCELLENT", "HIGH");

    /**
     * 自校验过滤：移除 LLM Map 分析中质量评分为 GOOD/EXCELLENT 的误判模糊提交。
     * <p>
     * VagueScanner 批量扫描时可能将清晰提交误判为模糊，Map 阶段逐条分析更精确。
     * 如果 Map 分析认为提交信息质量高，说明 VagueScanner 误判，应从模糊列表移除。
     *
     * @param vagueCommits 模糊提交列表
     * @param vagueReasons 模糊原因 map（会同步移除误判项）
     * @param analysisMap  LLM Map 分析结果
     * @return 过滤后的模糊提交列表
     */
    List<CommitInfo> filterFalsePositiveVague(List<CommitInfo> vagueCommits,
                                               Map<String, String> vagueReasons,
                                               Map<String, CommitAnalysisDto> analysisMap) {
        if (analysisMap.isEmpty() || vagueCommits.isEmpty()) {
            return vagueCommits;
        }

        List<CommitInfo> filtered = new ArrayList<>();
        int removedCount = 0;
        for (CommitInfo c : vagueCommits) {
            CommitAnalysisDto analysis = analysisMap.get(c.getHash());
            if (analysis != null && analysis.getMessageQuality() != null
                    && HIGH_QUALITY_MARKS.contains(analysis.getMessageQuality().toUpperCase())) {
                // Map 分析认为质量高，移除误判
                vagueReasons.remove(c.getShortHash());
                removedCount++;
                log.debug("自校验移除误判模糊提交: {} (质量={})", c.getShortHash(), analysis.getMessageQuality());
            } else {
                filtered.add(c);
            }
        }

        if (removedCount > 0) {
            log.info("LLM 自校验: 移除 {} 条误判模糊提交（{}/{} 保留）",
                    removedCount, filtered.size(), vagueCommits.size());
        }
        return filtered;
    }

    /**
     * LLM Map 阶段：对异常提交逐个语义分析。
     * <p>
     * 收集所有异常提交（巨型 + 跨域 + 模糊），逐个调用 LLM 分析意图、标签、风险、消息质量。
     * LLM 调用失败时返回空 map（优雅降级）。
     *
     * @param anomalyResult 异常检测结果
     * @param vagueCommits  模糊提交列表
     * @param llmKey        LLM API Key
     * @param llmUrl        LLM API URL
     * @param llmModel      LLM 模型名
     * @return commit hash → CommitAnalysisDto 映射
     */
    Map<String, CommitAnalysisDto> runLlmMapPhase(AnomalyFilter.AnomalyResult anomalyResult,
                                                   List<CommitInfo> vagueCommits,
                                                   String llmKey, String llmUrl, String llmModel) {
        Map<String, CommitInfo> anomalousCommits = new LinkedHashMap<>();

        // 收集巨型提交
        for (CommitInfo c : anomalyResult.getGiantCommits()) {
            anomalousCommits.putIfAbsent(c.getHash(), c);
        }
        // 收集跨域提交
        for (CommitInfo c : anomalyResult.getCrossDomainCommits()) {
            anomalousCommits.putIfAbsent(c.getHash(), c);
        }
        // 收集模糊提交
        for (CommitInfo c : vagueCommits) {
            anomalousCommits.putIfAbsent(c.getHash(), c);
        }

        if (anomalousCommits.isEmpty()) {
            log.info("无异常提交，跳过 LLM Map 阶段");
            return Map.of();
        }

        log.info("LLM Map 阶段: 分析 {} 条异常提交", anomalousCommits.size());
        Map<String, CommitAnalysisDto> resultMap = new LinkedHashMap<>();
        int total = anomalousCommits.size();
        int idx = 0;

        for (Map.Entry<String, CommitInfo> entry : anomalousCommits.entrySet()) {
            CommitInfo c = entry.getValue();
            idx++;
            sseProgressService.sendProgress("LLM_MAP",
                    "深度分析 " + idx + "/" + total + "（" + c.getShortHash() + "）...",
                    65 + (idx * 15 / Math.max(total, 1)));
            try {
                String diffSummary = buildDiffSummary(c);
                CommitAnalysisDto analysis = llmMapTranslator.analyzeCommit(
                        c.getAuthor(), c.getDate(), c.getMessage(),
                        diffSummary, llmUrl, llmKey, llmModel);
                resultMap.put(entry.getKey(), analysis);
            } catch (Exception e) {
                log.warn("LLM Map 分析提交 {} 失败，跳过: {}", c.getShortHash(), e.getMessage());
            }
        }

        log.info("LLM Map 阶段完成: {}/{} 条成功", resultMap.size(), anomalousCommits.size());
        return resultMap;
    }

    /**
     * 构建变更文件摘要（替代 diff 文本）。
     * CommitInfo 不存储 diff，用文件列表 + 行数统计作为 LLM 输入。
     */
    private String buildDiffSummary(CommitInfo c) {
        StringBuilder sb = new StringBuilder();
        sb.append("变更文件: ");
        if (c.getChangedFiles() != null && !c.getChangedFiles().isEmpty()) {
            sb.append(String.join(", ", c.getChangedFiles()));
        } else {
            sb.append("(无记录)");
        }
        sb.append("\n变更统计: +").append(c.getLinesAdded())
          .append(" -").append(c.getLinesDeleted())
          .append(" 行, ").append(c.getFilesChanged()).append(" 文件");
        if (c.getModules() != null && !c.getModules().isEmpty()) {
            sb.append("\n涉及模块: ").append(c.getModules());
        }
        return sb.toString();
    }

    /**
     * LLM Reduce 阶段：基于统计数据和 Map 结果生成全局洞察。
     */
    private GlobalInsightDto runLlmReduce(List<CommitInfo> commits,
                                           Map<String, CommitAnalysisDto> analysisMap,
                                           String llmKey, String llmUrl, String llmModel) {
        try {
            List<AuthorStatsDto> authorStats = buildAuthorStats(commits);
            List<ModuleStatsDto> moduleStats = buildModuleStats(commits);
            return llmReduceAnalyzer.generateInsight(authorStats, moduleStats, analysisMap, llmUrl, llmKey, llmModel);
        } catch (Exception e) {
            log.warn("LLM Reduce 分析失败，降级为默认洞察: {}", e.getMessage());
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
        if (repoRef.startsWith(HTTP_PREFIX) || repoRef.startsWith(HTTPS_PREFIX) || repoRef.startsWith(GIT_SSH_PREFIX)) {
            String name = repoRef;
            if (name.endsWith(GIT_URL_SUFFIX)) {
                name = name.substring(0, name.length() - GIT_URL_SUFFIX.length());
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
