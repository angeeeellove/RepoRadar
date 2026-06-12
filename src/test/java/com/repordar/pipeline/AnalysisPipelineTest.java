package com.repordar.pipeline;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnalysisPipeline 单元测试。
 * <p>
 * 测试管线编排器的核心逻辑：
 * - 日期过滤
 * - 作者统计构建
 * - 模块统计构建
 * - 活跃热力图构建
 * - 异常分组 DTO 构建
 * - 完整报告数据组装
 *
 * @author frank
 */
class AnalysisPipelineTest {

    private AnalysisPipeline pipeline;
    private List<CommitInfo> testCommits;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // 构建最小依赖（mock 不方便时直接用真实对象）
        AppProperties props = createTestProps();
        GitCloner gitCloner = new GitCloner();
        MetadataExtractor metadataExtractor = new MetadataExtractor(
                new com.repordar.git.ModuleDetector());
        AnomalyFilter anomalyFilter = new AnomalyFilter(props);
        VagueCommitDetector vagueDetector = new VagueCommitDetector(
                new com.repordar.anomaly.VagueScoringEngine(), null);
        com.fasterxml.jackson.databind.ObjectMapper objectMapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        LlmMapTranslator mapTranslator = new LlmMapTranslator(
                new com.repordar.llm.LlmClient(objectMapper), objectMapper);
        LlmReduceAnalyzer reduceAnalyzer = new LlmReduceAnalyzer(
                new com.repordar.llm.LlmClient(objectMapper), objectMapper);
        ReportGenerator reportGenerator = new ReportGenerator(objectMapper);
        SseProgressService sseService = new SseProgressService();

        pipeline = new AnalysisPipeline(
                gitCloner, metadataExtractor, anomalyFilter, vagueDetector,
                mapTranslator, reduceAnalyzer, reportGenerator, sseService, props, objectMapper
        );

        testCommits = createTestCommits();
    }

    // ==================== 日期过滤测试 ====================

    @Test
    void shouldFilterCommitsBySinceDate() {
        // Given: 提交列表，since = 2024-01-15
        // When: 过滤
        List<CommitInfo> filtered = invokeFilterByDate(testCommits, "2024-01-15", null);

        // Then: 应只保留 2024-01-15 及之后的提交（hash3: 01-15, hash4: 01-25）
        assertTrue(filtered.stream().allMatch(c ->
                c.getDate().compareTo("2024-01-15T00:00:00") >= 0));
        assertEquals(2, filtered.size());
    }

    @Test
    void shouldFilterCommitsByUntilDate() {
        // Given: 提交列表，until = 2024-01-20
        // When: 过滤
        List<CommitInfo> filtered = invokeFilterByDate(testCommits, null, "2024-01-20");

        // Then: 应只保留 2024-01-20 及之前的提交（hash1: 01-05, hash2: 01-10, hash3: 01-15）
        assertTrue(filtered.stream().allMatch(c ->
                c.getDate().compareTo("2024-01-20T23:59:59") <= 0));
        assertEquals(3, filtered.size());
    }

    @Test
    void shouldFilterCommitsByDateRange() {
        // Given: 提交列表，since = 2024-01-10, until = 2024-01-20
        // When: 过滤
        List<CommitInfo> filtered = invokeFilterByDate(testCommits, "2024-01-10", "2024-01-20");

        // Then: 应只保留范围内的提交
        assertEquals(2, filtered.size());
    }

    @Test
    void shouldReturnAllCommitsWhenNoDateFilter() {
        // Given: 提交列表，无日期过滤
        // When: 过滤
        List<CommitInfo> filtered = invokeFilterByDate(testCommits, null, null);

        // Then: 应返回全部提交
        assertEquals(4, filtered.size());
    }

    @Test
    void shouldReturnEmptyListWhenNoMatch() {
        // Given: 提交列表，since = 2099-01-01
        // When: 过滤
        List<CommitInfo> filtered = invokeFilterByDate(testCommits, "2099-01-01", null);

        // Then: 应返回空列表
        assertTrue(filtered.isEmpty());
    }

    // ==================== 作者统计测试 ====================

    @Test
    void shouldBuildAuthorStats() {
        // Given: 多个作者的提交列表
        // When: 构建作者统计
        List<AuthorStatsDto> stats = invokeBuildAuthorStats(testCommits);

        // Then: 应按提交数降序排列
        assertFalse(stats.isEmpty());
        for (int i = 1; i < stats.size(); i++) {
            assertTrue(stats.get(i - 1).getCommitCount() >= stats.get(i).getCommitCount());
        }
        // alice 有 2 次提交，bob 有 1 次，charlie 有 1 次
        assertEquals("alice", stats.get(0).getName());
        assertEquals(2, stats.get(0).getCommitCount());
    }

    @Test
    void shouldBuildAuthorStatsWithEmptyCommits() {
        // Given: 空提交列表
        // When: 构建作者统计
        List<AuthorStatsDto> stats = invokeBuildAuthorStats(Collections.emptyList());

        // Then: 应返回空列表
        assertTrue(stats.isEmpty());
    }

    // ==================== 模块统计测试 ====================

    @Test
    void shouldBuildModuleStats() {
        // Given: 包含多模块的提交列表
        // When: 构建模块统计
        List<ModuleStatsDto> stats = invokeBuildModuleStats(testCommits);

        // Then: 应包含各模块
        assertFalse(stats.isEmpty());
        // 验证有 order 和 user 模块
        Set<String> moduleNames = stats.stream()
                .map(ModuleStatsDto::getName)
                .collect(Collectors.toSet());
        assertTrue(moduleNames.contains("order"));
        assertTrue(moduleNames.contains("user"));
    }

    @Test
    void shouldBuildModuleStatsWithEmptyCommits() {
        // Given: 空提交列表
        // When: 构建模块统计
        List<ModuleStatsDto> stats = invokeBuildModuleStats(Collections.emptyList());

        // Then: 应返回空列表
        assertTrue(stats.isEmpty());
    }

    // ==================== 活跃热力图测试 ====================

    @Test
    void shouldBuildActivityHeatmap() {
        // Given: 包含日期时间的提交列表
        // When: 构建热力图
        ActivityHeatmapDto heatmap = invokeBuildActivityHeatmap(testCommits);

        // Then: 应有数据点
        assertNotNull(heatmap);
        assertFalse(heatmap.getData().isEmpty());
        assertTrue(heatmap.getMaxCount() > 0);
    }

    @Test
    void shouldBuildActivityHeatmapWithEmptyCommits() {
        // Given: 空提交列表
        // When: 构建热力图
        ActivityHeatmapDto heatmap = invokeBuildActivityHeatmap(Collections.emptyList());

        // Then: maxCount 应为 0
        assertNotNull(heatmap);
        assertEquals(0, heatmap.getMaxCount());
        assertTrue(heatmap.getData().isEmpty());
    }

    // ==================== 异常分组 DTO 测试 ====================

    @Test
    void shouldBuildAnomalyGroupFromAnomalyResult() {
        // Given: AnomalyResult 和 vagueCommits
        AnomalyFilter.AnomalyResult anomalyResult = AnomalyFilter.AnomalyResult.empty();
        List<CommitInfo> vagueCommits = Collections.singletonList(testCommits.get(0));

        // When: 构建 AnomalyGroupDto
        AnomalyGroupDto group = invokeBuildAnomalyGroup(anomalyResult, vagueCommits);

        // Then: 应包含各异常类型的列表
        assertNotNull(group);
        assertNotNull(group.getGiantCommits());
        assertNotNull(group.getVolatileFiles());
        assertNotNull(group.getCrossDomainCommits());
        assertNotNull(group.getVagueCommits());
        assertEquals(1, group.getVagueCommits().size());
        // 检测标准说明应已填充
        assertNotNull(group.getGiantCriteria());
        assertNotNull(group.getVolatileCriteria());
        assertNotNull(group.getCrossDomainCriteria());
        assertNotNull(group.getVagueCriteria());
        assertFalse(group.getGiantCriteria().isEmpty());
    }

    // ==================== 完整报告数据组装测试 ====================

    @Test
    void shouldAssembleReportData() {
        // Given: 分析所需的所有数据
        AnomalyFilter.AnomalyResult anomalyResult = AnomalyFilter.AnomalyResult.empty();
        List<CommitInfo> vagueCommits = Collections.emptyList();
        GlobalInsightDto insight = new GlobalInsightDto("测试总结",
                Collections.singletonList("建议1"), 80);
        boolean llmEnabled = false;
        String llmModel = "";
        long durationMs = 1000L;

        // When: 组装报告数据
        ReportDataDto reportData = invokeAssembleReportData(
                "test-repo", "https://github.com/test/repo.git", "main",
                "2024-01-01", "2024-01-31",
                testCommits, anomalyResult, vagueCommits,
                insight, llmEnabled, llmModel, durationMs
        );

        // Then: 应包含所有顶层字段
        assertNotNull(reportData);
        assertNotNull(reportData.getRepository());
        assertEquals("test-repo", reportData.getRepository().getName());
        assertEquals(4, reportData.getRepository().getTotalCommits());
        assertNotNull(reportData.getAuthors());
        assertNotNull(reportData.getModules());
        assertNotNull(reportData.getAnomalies());
        assertNotNull(reportData.getActivityHeatmap());
        assertNotNull(reportData.getMeta());
        assertFalse(reportData.getMeta().isLlmEnabled());
        assertEquals(1000L, reportData.getMeta().getAnalysisDurationMs());
    }

    // ==================== 报告生成端到端测试 ====================

    @Test
    void shouldGenerateReportHtmlFile() throws IOException {
        // Given: 完整的 ReportDataDto 和模板
        ReportDataDto data = new ReportDataDto();
        data.setRepository(new RepositoryInfoDto("test-repo", "https://example.com",
                "main", new RepositoryInfoDto.AnalysisPeriod("2024-01-01", "2024-01-31"),
                10, 3, 20));
        data.setAuthors(Collections.emptyList());
        data.setModules(Collections.emptyList());
        data.setAnomalies(new AnomalyGroupDto(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                "测试标准", "测试标准", "测试标准", "测试标准"));
        data.setGlobalInsight(new GlobalInsightDto("总结", Collections.emptyList(), 80));
        data.setActivityHeatmap(new ActivityHeatmapDto(Collections.emptyList(), 0));
        data.setMeta(new AnalysisMetaDto("2024-01-01T00:00:00", false, "", 1000L, "1.0.0"));

        String templateHtml = "<html><head><script>const DATA = __INJECT_DATA__;</script></head><body></body></html>";

        // When: 生成报告
        String reportPath = pipeline.generateReport(data, tempDir.toString(), templateHtml);

        // Then: 报告文件应存在
        assertNotNull(reportPath);
        Path reportFile = Path.of(reportPath);
        assertTrue(Files.exists(reportFile));

        // 内容应包含 JSON 数据
        String content = Files.readString(reportFile);
        assertTrue(content.contains("test-repo"));
        // 验证 __INJECT_DATA__ 占位符已被替换
        assertFalse(content.contains("__INJECT_DATA__"));
        // 验证 JSON 数据被正确注入到 script 标签内
        assertTrue(content.contains("const DATA = {"));
    }

    // ==================== 辅助方法 ====================

    /**
     * 反射调用 filterByDate 方法。
     */
    private List<CommitInfo> invokeFilterByDate(List<CommitInfo> commits, String since, String until) {
        try {
            Method method = AnalysisPipeline.class.getDeclaredMethod(
                    "filterByDate", List.class, String.class, String.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<CommitInfo> result = (List<CommitInfo>) method.invoke(pipeline, commits, since, until);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("反射调用 filterByDate 失败", e);
        }
    }

    /**
     * 反射调用 buildAuthorStats 方法。
     */
    private List<AuthorStatsDto> invokeBuildAuthorStats(List<CommitInfo> commits) {
        try {
            Method method = AnalysisPipeline.class.getDeclaredMethod(
                    "buildAuthorStats", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<AuthorStatsDto> result = (List<AuthorStatsDto>) method.invoke(pipeline, commits);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("反射调用 buildAuthorStats 失败", e);
        }
    }

    /**
     * 反射调用 buildModuleStats 方法。
     */
    private List<ModuleStatsDto> invokeBuildModuleStats(List<CommitInfo> commits) {
        try {
            Method method = AnalysisPipeline.class.getDeclaredMethod(
                    "buildModuleStats", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ModuleStatsDto> result = (List<ModuleStatsDto>) method.invoke(pipeline, commits);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("反射调用 buildModuleStats 失败", e);
        }
    }

    /**
     * 反射调用 buildActivityHeatmap 方法。
     */
    private ActivityHeatmapDto invokeBuildActivityHeatmap(List<CommitInfo> commits) {
        try {
            Method method = AnalysisPipeline.class.getDeclaredMethod(
                    "buildActivityHeatmap", List.class);
            method.setAccessible(true);
            return (ActivityHeatmapDto) method.invoke(pipeline, commits);
        } catch (Exception e) {
            throw new RuntimeException("反射调用 buildActivityHeatmap 失败", e);
        }
    }

    /**
     * 反射调用 buildAnomalyGroup 方法。
     */
    private AnomalyGroupDto invokeBuildAnomalyGroup(AnomalyFilter.AnomalyResult anomalyResult,
                                                     List<CommitInfo> vagueCommits) {
        try {
            Method method = AnalysisPipeline.class.getDeclaredMethod(
                    "buildAnomalyGroup", AnomalyFilter.AnomalyResult.class, List.class, Map.class, Map.class, boolean.class);
            method.setAccessible(true);
            return (AnomalyGroupDto) method.invoke(pipeline, anomalyResult, vagueCommits, Map.of(), Map.of(), false);
        } catch (Exception e) {
            throw new RuntimeException("反射调用 buildAnomalyGroup 失败", e);
        }
    }

    /**
     * 反射调用 assembleReportData 方法。
     */
    private ReportDataDto invokeAssembleReportData(String repoName, String repoUrl, String branch,
                                                    String since, String until,
                                                    List<CommitInfo> commits,
                                                    AnomalyFilter.AnomalyResult anomalyResult,
                                                    List<CommitInfo> vagueCommits,
                                                    GlobalInsightDto insight,
                                                    boolean llmEnabled, String llmModel,
                                                    long durationMs) {
        try {
            Method method = AnalysisPipeline.class.getDeclaredMethod(
                    "assembleReportData", String.class, String.class, String.class,
                    String.class, String.class, List.class,
                    AnomalyFilter.AnomalyResult.class, List.class, Map.class, Map.class,
                    GlobalInsightDto.class, boolean.class, String.class, long.class);
            method.setAccessible(true);
            return (ReportDataDto) method.invoke(pipeline, repoName, repoUrl, branch,
                    since, until, commits, anomalyResult, vagueCommits, Map.of(), Map.of(),
                    insight, llmEnabled, llmModel, durationMs);
        } catch (Exception e) {
            throw new RuntimeException("反射调用 assembleReportData 失败", e);
        }
    }

    /**
     * 创建测试用配置。
     */
    private AppProperties createTestProps() {
        AppProperties props = new AppProperties();
        AppProperties.Anomaly anomaly = new AppProperties.Anomaly();
        anomaly.setGiantCommitThreshold(100);
        anomaly.setVolatileWindowDays(7);
        anomaly.setVolatileThreshold(5);
        anomaly.setCrossDomainThreshold(3);
        props.setAnomaly(anomaly);

        AppProperties.Llm llm = new AppProperties.Llm();
        llm.setBaseUrl("https://api.example.com/v1");
        llm.setModelName("test-model");
        props.setLlm(llm);

        AppProperties.Report report = new AppProperties.Report();
        report.setOutputDir("./reports");
        props.setReport(report);

        return props;
    }

    /**
     * 创建测试用提交数据。
     */
    private List<CommitInfo> createTestCommits() {
        List<CommitInfo> commits = new ArrayList<>();

        commits.add(createCommitInfo("hash1", "alice", "alice@example.com",
                "2024-01-05T10:00:00", "feat(order): 添加订单创建功能",
                50, 2, Arrays.asList("src/main/java/com/example/order/OrderService.java"),
                new HashSet<>(Arrays.asList("order"))));

        commits.add(createCommitInfo("hash2", "bob", "bob@example.com",
                "2024-01-10T14:30:00", "fix(user): 修复用户登录 NPE",
                20, 1, Arrays.asList("src/main/java/com/example/user/UserService.java"),
                new HashSet<>(Arrays.asList("user"))));

        commits.add(createCommitInfo("hash3", "alice", "alice@example.com",
                "2024-01-15T09:15:00", "refactor(order): 重构订单处理流程",
                80, 3, Arrays.asList(
                        "src/main/java/com/example/order/OrderService.java",
                        "src/main/java/com/example/order/OrderRepository.java",
                        "src/main/java/com/example/order/OrderController.java"),
                new HashSet<>(Arrays.asList("order"))));

        commits.add(createCommitInfo("hash4", "charlie", "charlie@example.com",
                "2024-01-25T16:45:00", "chore: 优化了代码",
                5, 1, Arrays.asList("src/main/java/com/example/common/Utils.java"),
                new HashSet<>(Arrays.asList("common"))));

        return commits;
    }

    /**
     * 创建 CommitInfo 辅助方法。
     */
    private CommitInfo createCommitInfo(String hash, String author, String email,
                                         String date, String message,
                                         int totalLines, int filesChanged,
                                         List<String> changedFiles, Set<String> modules) {
        CommitInfo info = new CommitInfo();
        info.setHash(hash);
        info.setShortHash(hash.length() >= 7 ? hash.substring(0, 7) : hash);
        info.setAuthor(author);
        info.setAuthorEmail(email);
        info.setDate(date);
        info.setMessage(message);
        info.setTotalLines(totalLines);
        info.setFilesChanged(filesChanged);
        info.setLinesAdded(totalLines);
        info.setLinesDeleted(0);
        info.setChangedFiles(changedFiles);
        info.setModules(modules);
        return info;
    }
}
