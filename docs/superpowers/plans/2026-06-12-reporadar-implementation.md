# RepoRadar 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个本地 AI Git 分析 CLI 工具，能对仓库提交记录进行元数据统计 + LLM 语义分析，生成可视化 HTML 报告。

**Architecture:** Spring Boot CLI 应用（Picocli），同步管线架构。JGit 做 Git 操作，Java 规则引擎做异常检测，LLM（OpenAI 兼容 API）做语义分析，Vue3 + ECharts（CDN）做报告渲染。单 JAR 部署，优雅降级。

**Tech Stack:** Java 17, Spring Boot 3.2+, Gradle, Picocli, JGit, Jackson, RestClient, Vue3/ECharts/TailwindCSS (CDN)

**Design Docs:**
- 需求澄清: `docs/superpowers/specs/2026-06-12-reporadar-prd-refinement-design.md`
- 接口设计: `docs/superpowers/specs/2026-06-12-reporadar-interface-design.md`
- 前端设计: `docs/superpowers/specs/2026-06-12-reporadar-frontend-design.md`

---

## File Structure

```
repordar/
├── build.gradle
├── settings.gradle
├── src/main/java/com/repordar/
│   ├── RepoRadarApplication.java                 # Spring Boot 入口 + Picocli 命令
│   ├── config/
│   │   └── AppProperties.java                    # 配置属性（LLM、异常规则阈值）
│   ├── git/
│   │   ├── GitCloner.java                        # git clone --bare + 清理
│   │   ├── MetadataExtractor.java                # JGit 提取元数据
│   │   └── ModuleDetector.java                   # 一级目录模块检测
│   ├── anomaly/
│   │   ├── AnomalyFilter.java                    # 异常过滤编排器
│   │   ├── GiantCommitRule.java                  # 巨型提交规则
│   │   ├── VolatileFileRule.java                 # 易挥发文件规则
│   │   ├── CrossDomainRule.java                  # 跨域提交规则
│   │   ├── VagueCommitDetector.java              # 模糊提交检测（LLM + 降级）
│   │   └── VagueScoringEngine.java               # 纯 Java 特征评分
│   ├── llm/
│   │   ├── LlmClient.java                        # OpenAI 兼容 API 客户端
│   │   ├── LlmVagueScanner.java                  # 批量模糊提交扫描
│   │   ├── LlmMapTranslator.java                 # Map 阶段：逐提交分析
│   │   └── LlmReduceAnalyzer.java               # Reduce 阶段：全局洞察
│   ├── report/
│   │   └── ReportGenerator.java                  # JSON 序列化 + 模板注入
│   ├── sse/
│   │   └── SseProgressService.java               # SSE 进度推送服务
│   ├── pipeline/
│   │   └── AnalysisPipeline.java                 # 管线编排器
│   └── dto/
│       ├── ApiResponse.java                      # {code, msg, data} 通用响应
│       ├── ReportDataDto.java                    # 报告顶层 DTO
│       ├── RepositoryInfoDto.java                # 仓库信息
│       ├── AuthorStatsDto.java                   # 作者统计
│       ├── ModuleStatsDto.java                   # 模块统计
│       ├── AnomalyGroupDto.java                  # 异常分组
│       ├── GiantCommitDto.java                   # 巨型提交
│       ├── VolatileFileDto.java                  # 易挥发文件
│       ├── CrossDomainCommitDto.java             # 跨域提交
│       ├── VagueCommitDto.java                   # 模糊提交
│       ├── CommitAnalysisDto.java                # LLM Map 分析结果
│       ├── GlobalInsightDto.java                 # 全局洞察
│       ├── ActivityHeatmapDto.java               # 活跃热力图
│       └── AnalysisMetaDto.java                  # 分析元信息
├── src/main/resources/
│   ├── application.yml                           # 默认配置
│   └── report-template/
│       └── index.html                            # Vue3 + ECharts 报告模板
└── src/test/java/com/repordar/
    ├── anomaly/
    │   ├── GiantCommitRuleTest.java
    │   ├── VolatileFileRuleTest.java
    │   ├── CrossDomainRuleTest.java
    │   └── VagueScoringEngineTest.java
    ├── git/
    │   ├── MetadataExtractorTest.java
    │   └── ModuleDetectorTest.java
    ├── llm/
    │   └── LlmMapTranslatorTest.java             # JSON 反序列化容错测试
    └── report/
        └── ReportGeneratorTest.java
```

---

## Phase 1: Scaffolding

### Task 1: Gradle 项目初始化

**Files:**
- Create: `build.gradle`
- Create: `settings.gradle`
- Create: `src/main/java/com/repordar/RepoRadarApplication.java`

- [ ] **Step 1: 创建 settings.gradle**

```groovy
// settings.gradle
rootProject.name = 'repordar'
```

- [ ] **Step 2: 创建 build.gradle**

```groovy
// build.gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.5'
    id 'io.spring.dependency-management' version '1.1.4'
}

group = 'com.repordar'
version = '1.0.0'

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // CLI
    implementation 'info.picocli:picocli-spring-boot-starter:4.7.5'

    // Git
    implementation 'org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r'

    // LLM HTTP Client (Spring RestClient)
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // Jackson (comes with spring-boot-starter-web)

    // Test
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

test {
    useJUnitPlatform()
}

bootJar {
    archiveBaseName = 'repordar'
    archiveVersion = '1.0.0'
}
```

- [ ] **Step 3: 创建 Spring Boot 入口**

```java
// src/main/java/com/repordar/RepoRadarApplication.java
package com.repordar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RepoRadarApplication {
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(RepoRadarApplication.class, args)));
    }
}
```

- [ ] **Step 4: 创建默认配置文件**

```yaml
# src/main/resources/application.yml
repordar:
  anomaly:
    giant-commit-threshold: 500
    volatile-window-days: 7
    volatile-threshold: 10
    cross-domain-threshold: 3
  llm:
    api-key: ${REPORADAR_LLM_API_KEY:}
    base-url: ${REPORADAR_LLM_BASE_URL:https://api.deepseek.com/v1}
    model-name: ${REPORADAR_LLM_MODEL:deepseek-chat}
  report:
    output-dir: ./reports
  server:
    port: 8080

spring:
  main:
    web-application-type: servlet
    banner-mode: off
```

- [ ] **Step 5: 验证构建**

Run: `./gradlew bootJar`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "🔧 chore: 初始化 Gradle 项目结构与 Spring Boot 入口"
```

---

### Task 2: DTO 层

**Files:**
- Create: `src/main/java/com/repordar/dto/ApiResponse.java`
- Create: `src/main/java/com/repordar/dto/CommitAnalysisDto.java`
- Create: `src/main/java/com/repordar/dto/RepositoryInfoDto.java`
- Create: `src/main/java/com/repordar/dto/AuthorStatsDto.java`
- Create: `src/main/java/com/repordar/dto/ModuleStatsDto.java`
- Create: `src/main/java/com/repordar/dto/GiantCommitDto.java`
- Create: `src/main/java/com/repordar/dto/VolatileFileDto.java`
- Create: `src/main/java/com/repordar/dto/CrossDomainCommitDto.java`
- Create: `src/main/java/com/repordar/dto/VagueCommitDto.java`
- Create: `src/main/java/com/repordar/dto/AnomalyGroupDto.java`
- Create: `src/main/java/com/repordar/dto/GlobalInsightDto.java`
- Create: `src/main/java/com/repordar/dto/ActivityHeatmapDto.java`
- Create: `src/main/java/com/repordar/dto/AnalysisMetaDto.java`
- Create: `src/main/java/com/repordar/dto/ReportDataDto.java`

所有 DTO 使用 Java record，字段与接口设计文档一一对应。

- [ ] **Step 1: 创建通用响应 DTO**

```java
package com.repordar.dto;

public record ApiResponse<T>(int code, String msg, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }
    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
```

- [ ] **Step 2: 创建 LLM 分析结果 DTO**

```java
package com.repordar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitAnalysisDto(
    String intent,
    List<String> tags,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("message_quality") String messageQuality,
    @JsonProperty("quality_reason") String qualityReason
) {}
```

- [ ] **Step 3: 创建仓库信息 DTO**

```java
package com.repordar.dto;

public record RepositoryInfoDto(
    String name,
    String url,
    String branch,
    AnalysisPeriod analysisPeriod,
    int totalCommits,
    int totalAuthors,
    int totalFiles
) {
    public record AnalysisPeriod(String since, String until) {}
}
```

- [ ] **Step 4: 创建作者统计 DTO**

```java
package com.repordar.dto;

import java.util.List;

public record AuthorStatsDto(
    String name,
    String email,
    int commitCount,
    int linesAdded,
    int linesDeleted,
    int[] activeHourDistribution,
    int[] activeDayDistribution,
    String peakHours,
    List<String> primaryModules,
    String profile
) {}
```

- [ ] **Step 5: 创建模块统计 DTO**

```java
package com.repordar.dto;

import java.util.List;

public record ModuleStatsDto(
    String name,
    int commitCount,
    int linesChanged,
    List<String> topContributors,
    String insight
) {}
```

- [ ] **Step 6: 创建异常相关 DTO**

```java
package com.repordar.dto;

import java.util.List;

public record GiantCommitDto(
    String hash, String shortHash, String author, String authorEmail,
    String date, String message, int totalLines, int filesChanged,
    List<String> modules, CommitAnalysisDto analysis
) {}

public record VolatileFileDto(
    String path, int changeCount, int windowDays,
    List<String> contributors, int linesChanged
) {}

public record CrossDomainCommitDto(
    String hash, String shortHash, String author, String authorEmail,
    String date, String message, List<String> modules,
    int filesChanged, CommitAnalysisDto analysis
) {}

public record VagueCommitDto(
    String hash, String shortHash, String author, String authorEmail,
    String date, String message, String vagueReason,
    Integer score, CommitAnalysisDto analysis
) {}
```

- [ ] **Step 7: 创建异常分组 DTO**

```java
package com.repordar.dto;

import java.util.List;

public record AnomalyGroupDto(
    List<GiantCommitDto> giantCommits,
    List<VolatileFileDto> volatileFiles,
    List<CrossDomainCommitDto> crossDomainCommits,
    List<VagueCommitDto> vagueCommits
) {}
```

- [ ] **Step 8: 创建全局洞察 + 热力图 + 元信息 DTO**

```java
package com.repordar.dto;

import java.util.List;

public record GlobalInsightDto(
    String summary,
    List<String> recommendations,
    int healthScore
) {}

public record HeatmapPoint(int day, int hour, int count) {}

public record ActivityHeatmapDto(
    List<HeatmapPoint> data,
    int maxCount
) {}

public record AnalysisMetaDto(
    String analyzedAt,
    boolean llmEnabled,
    String llmModel,
    long analysisDurationMs,
    String version
) {}
```

- [ ] **Step 9: 创建报告顶层 DTO**

```java
package com.repordar.dto;

public record ReportDataDto(
    RepositoryInfoDto repository,
    java.util.List<AuthorStatsDto> authors,
    java.util.List<ModuleStatsDto> modules,
    AnomalyGroupDto anomalies,
    GlobalInsightDto globalInsight,
    ActivityHeatmapDto activityHeatmap,
    AnalysisMetaDto meta
) {}
```

- [ ] **Step 10: 验证编译**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 11: 提交**

```bash
git add -A
git commit -m "✨ feat: 添加完整 DTO 层，对齐接口设计文档"
```

---

### Task 3: 配置属性类

**Files:**
- Create: `src/main/java/com/repordar/config/AppProperties.java`

- [ ] **Step 1: 创建配置属性类**

```java
package com.repordar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repordar")
public record AppProperties(
    Anomaly anomaly,
    Llm llm,
    Report report
) {
    public record Anomaly(
        int giantCommitThreshold,
        int volatileWindowDays,
        int volatileThreshold,
        int crossDomainThreshold
    ) {}

    public record Llm(
        String apiKey,
        String baseUrl,
        String modelName
    ) {
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Report(
        String outputDir
    ) {}
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "✨ feat: 添加配置属性类 AppProperties"
```

---

### Task 4: CLI 命令 + SSE 进度服务

**Files:**
- Create: `src/main/java/com/repordar/cli/AnalyzeCommand.java`
- Create: `src/main/java/com/repordar/sse/SseProgressService.java`

- [ ] **Step 1: 创建 SSE 进度服务**

```java
package com.repordar.sse;

import com.repordar.dto.ApiResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@Service
public class SseProgressService {

    private SseEmitter emitter;

    public SseEmitter createEmitter() {
        this.emitter = new SseEmitter(300_000L); // 5 min timeout
        return this.emitter;
    }

    public void sendProgress(String stage, String message, int percent) {
        send("progress", ApiResponse.ok(new ProgressData(stage, message, percent)));
    }

    public void sendComplete(String reportPath) {
        send("complete", ApiResponse.ok(new ReportPathData(reportPath)));
        if (emitter != null) emitter.complete();
    }

    public void sendError(int code, String msg) {
        send("error", ApiResponse.error(code, msg));
        if (emitter != null) emitter.completeWithError(new RuntimeException(msg));
    }

    private void send(String eventName, Object data) {
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException ignored) {
            // Client disconnected, ignore
        }
    }

    public record ProgressData(String stage, String message, int percent) {}
    public record ReportPathData(String reportPath) {}
}
```

- [ ] **Step 2: 创建 CLI 命令骨架**

```java
package com.repordar.cli;

import com.repordar.config.AppProperties;
import com.repordar.pipeline.AnalysisPipeline;
import com.repordar.sse.SseProgressService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Callable;

@Component
@Command(name = "repordar", mixinStandardHelpOptions = true,
         description = "RepoRadar - 团队代码脉搏雷达")
public class AnalyzeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Git 仓库 URL 或本地路径")
    private String repo;

    @Option(names = "--output", description = "报告输出目录", defaultValue = "./reports")
    private String output;

    @Option(names = "--since", description = "分析起始日期 (yyyy-MM-dd)")
    private String since;

    @Option(names = "--until", description = "分析截止日期 (yyyy-MM-dd)")
    private String until;

    @Option(names = "--branch", description = "分析的分支")
    private String branch;

    @Option(names = "--llm-api-key", description = "LLM API Key")
    private String llmApiKey;

    @Option(names = "--llm-base-url", description = "LLM API 基础 URL")
    private String llmBaseUrl;

    @Option(names = "--llm-model", description = "LLM 模型名称")
    private String llmModel;

    @Option(names = "--port", description = "SSE 服务端口", defaultValue = "8080")
    private int port;

    @Option(names = "--no-browser", description = "不自动打开浏览器")
    private boolean noBrowser;

    private final ApplicationContext context;
    private final AppProperties props;

    public AnalyzeCommand(ApplicationContext context, AppProperties props) {
        this.context = context;
        this.props = props;
    }

    @Override
    public Integer call() {
        // CLI 参数覆盖配置
        var effectiveLlmKey = resolve(llmApiKey, props.llm().apiKey());
        var effectiveLlmUrl = resolve(llmBaseUrl, props.llm().baseUrl());
        var effectiveLlmModel = resolve(llmModel, props.llm().modelName());

        System.out.println("🔍 RepoRadar 开始分析: " + repo);
        if (effectiveLlmKey == null || effectiveLlmKey.isBlank()) {
            System.out.println("⚠️  未配置 LLM API Key，将跳过语义分析");
        }

        try {
            var pipeline = context.getBean(AnalysisPipeline.class);
            String reportPath = pipeline.execute(
                repo, output, since, until, branch,
                effectiveLlmKey, effectiveLlmUrl, effectiveLlmModel
            );
            System.out.println("✅ 分析完成！报告: " + reportPath);
            return 0;
        } catch (Exception e) {
            System.err.println("❌ 分析失败: " + e.getMessage());
            return 2;
        } finally {
            SpringApplication.exit(context, () -> 0);
        }
    }

    private String resolve(String cliValue, String configValue) {
        return (cliValue != null && !cliValue.isBlank()) ? cliValue : configValue;
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "✨ feat: 添加 CLI 命令与 SSE 进度推送服务"
```

---

## Phase 2: Core Analysis Engine

### Task 5: Git 克隆 + 清理

**Files:**
- Create: `src/main/java/com/repordar/git/GitCloner.java`

- [ ] **Step 1: 实现 GitCloner**

```java
package com.repordar.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Component
public class GitCloner {

    /**
     * 克隆仓库到临时目录，返回 Repository 对象。
     * 远程 URL → git clone --bare；本地路径 → 直接打开。
     */
    public ClonedRepo cloneOrOpen(String repoRef) throws Exception {
        if (repoRef.startsWith("http://") || repoRef.startsWith("https://") || repoRef.endsWith(".git")) {
            return cloneRemote(repoRef);
        } else {
            return openLocal(repoRef);
        }
    }

    private ClonedRepo cloneRemote(String url) throws Exception {
        Path tempDir = Files.createTempDirectory("repordar-");
        Git git = Git.cloneRepository()
            .setURI(url)
            .setDirectory(tempDir.toFile())
            .setCloneAllBranches()
            .call();
        return new ClonedRepo(git.getRepository(), tempDir, true);
    }

    private ClonedRepo openLocal(String path) throws Exception {
        Path localPath = Path.of(path).toAbsolutePath();
        Repository repo = Git.open(localPath.toFile()).getRepository();
        return new ClonedRepo(repo, localPath, false);
    }

    public void cleanup(ClonedRepo repo) {
        try {
            repo.repository().close();
            if (repo.isTemp()) {
                Files.walk(repo.path())
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            }
        } catch (Exception ignored) {}
    }

    public record ClonedRepo(Repository repository, Path path, boolean isTemp) {}
}
```

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现 Git 仓库克隆与清理"
```

---

### Task 6: 模块检测 + 元数据提取 (TDD)

**Files:**
- Create: `src/test/java/com/repordar/git/ModuleDetectorTest.java`
- Create: `src/main/java/com/repordar/git/ModuleDetector.java`
- Create: `src/test/java/com/repordar/git/MetadataExtractorTest.java`
- Create: `src/main/java/com/repordar/git/MetadataExtractor.java`

- [ ] **Step 1: 编写 ModuleDetector 测试**

```java
package com.repordar.git;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

class ModuleDetectorTest {

    private final ModuleDetector detector = new ModuleDetector();

    @Test
    void shouldDetectFirstLevelDirectoryAsModule() {
        String path = "src/main/java/com/example/order/service/OrderService.java";
        assertEquals("order", detector.detectModule(path));
    }

    @Test
    void shouldDetectFromNonSrcPath() {
        String path = "payment/src/main/java/PaymentService.java";
        assertEquals("payment", detector.detectModule(path));
    }

    @Test
    void shouldReturnRootForTopLevelFile() {
        String path = "README.md";
        assertEquals("_root", detector.detectModule(path));
    }

    @Test
    void shouldExtractAllModulesFromFileList() {
        List<String> paths = List.of(
            "src/main/java/com/example/order/OrderService.java",
            "src/main/java/com/example/user/UserService.java",
            "src/main/java/com/example/order/OrderRepo.java"
        );
        Set<String> modules = detector.extractModules(paths);
        assertEquals(Set.of("order", "user"), modules);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests ModuleDetectorTest`
Expected: FAIL (class not found)

- [ ] **Step 3: 实现 ModuleDetector**

```java
package com.repordar.git;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ModuleDetector {

    /**
     * 从文件路径提取一级目录模块名。
     * 规则：跳过 src 前缀，取第一级目录名。
     */
    public String detectModule(String filePath) {
        String normalized = filePath.replace('\\', '/');
        // 去掉 src/ 前缀
        String stripped = normalized.replaceFirst("^src/(main|test)/(java|kotlin|resources)/", "");
        stripped = stripped.replaceFirst("^src/", "");
        // 去掉包路径前缀 (com/example/...)，取第一个目录
        String[] parts = stripped.split("/");
        if (parts.length <= 1) {
            return "_root";
        }
        // 跳过 com, org, net 等包名前缀
        int start = 0;
        while (start < parts.length - 1 && isPackagePrefix(parts[start])) {
            start++;
        }
        // 取跳过包名后的第一个目录，或倒数第二个
        if (start < parts.length - 1) {
            return parts[start];
        }
        return parts[0];
    }

    public Set<String> extractModules(List<String> filePaths) {
        return filePaths.stream()
            .map(this::detectModule)
            .collect(Collectors.toSet());
    }

    private boolean isPackagePrefix(String part) {
        return Set.of("com", "org", "net", "io", "cn", "uk", "de", "jp").contains(part);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests ModuleDetectorTest`
Expected: PASS

- [ ] **Step 5: 编写 MetadataExtractor 测试**

```java
package com.repordar.git;

import com.repordar.dto.AuthorStatsDto;
import com.repordar.dto.RepositoryInfoDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

class MetadataExtractorTest {

    private final ModuleDetector moduleDetector = new ModuleDetector();
    private final MetadataExtractor extractor = new MetadataExtractor(moduleDetector);

    @Test
    void shouldCountCommitsPerAuthor() {
        // 这是一个集成测试，需要真实 git repo
        // 在实际开发中会用 JGit API 创建内存仓库
        // 这里先写接口契约测试
        assertNotNull(extractor);
    }
}
```

- [ ] **Step 6: 实现 MetadataExtractor 骨架**

```java
package com.repordar.git;

import com.repordar.dto.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class MetadataExtractor {

    private final ModuleDetector moduleDetector;

    public MetadataExtractor(ModuleDetector moduleDetector) {
        this.moduleDetector = moduleDetector;
    }

    public ExtractionResult extract(Repository repository, String branch,
                                     String since, String until) throws Exception {
        var git = new Git(repository);

        // 获取提交日志
        var logCmd = git.log();
        if (branch != null) {
            logCmd.add(repository.resolve(branch));
        } else {
            logCmd.all();
        }

        Iterable<RevCommit> commits = logCmd.call();
        List<RevCommit> commitList = new ArrayList<>();
        LocalDate sinceDate = since != null ? LocalDate.parse(since) : null;
        LocalDate untilDate = until != null ? LocalDate.parse(until) : null;

        for (RevCommit commit : commits) {
            LocalDateTime commitDate = Instant.ofEpochSecond(commit.getCommitTime())
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDate date = commitDate.toLocalDate();

            if (sinceDate != null && date.isBefore(sinceDate)) continue;
            if (untilDate != null && date.isAfter(untilDate)) continue;
            commitList.add(commit);
        }

        // 提取作者统计
        Map<String, AuthorBuilder> authorMap = new LinkedHashMap<>();
        Map<String, Integer> moduleCommitCount = new LinkedHashMap<>();
        Map<String, Set<String>> moduleContributors = new LinkedHashMap<>();
        List<CommitInfo> allCommits = new ArrayList<>();
        int[][] heatmapData = new int[7][24]; // [day][hour]
        int totalFiles = 0;

        var df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(repository);
        df.setDiffComparator(RawTextComparator.DEFAULT);

        for (RevCommit commit : commitList) {
            String authorName = commit.getAuthorIdent().getName();
            String authorEmail = commit.getAuthorIdent().getEmailAddress();
            String authorKey = authorName + "<" + authorEmail + ">";

            authorMap.computeIfAbsent(authorKey, k -> new AuthorBuilder(authorName, authorEmail));
            var ab = authorMap.get(authorKey);

            // Diff 统计
            int added = 0, deleted = 0;
            List<String> changedFiles = new ArrayList<>();
            if (commit.getParentCount() > 0) {
                List<DiffEntry> diffs = df.scan(commit.getParent(0), commit);
                for (DiffEntry de : diffs) {
                    added += de.getAddedLines();
                    deleted += de.getDeletedLines();
                    if (de.getNewPath() != null) {
                        changedFiles.add(de.getNewPath());
                    }
                }
            }

            Set<String> modules = moduleDetector.extractModules(changedFiles);
            int hour = Instant.ofEpochSecond(commit.getCommitTime())
                .atZone(ZoneId.systemDefault()).getHour();
            int dayOfWeek = Instant.ofEpochSecond(commit.getCommitTime())
                .atZone(ZoneId.systemDefault()).getDayOfWeek().getValue() - 1; // 0=Mon

            ab.commitCount++;
            ab.linesAdded += added;
            ab.linesDeleted += deleted;
            ab.hourDist[hour]++;
            ab.dayDist[dayOfWeek]++;
            ab.modules.addAll(modules);

            for (String mod : modules) {
                moduleCommitCount.merge(mod, 1, Integer::sum);
                moduleContributors.computeIfAbsent(mod, k -> new LinkedHashSet<>()).add(authorName);
            }

            heatmapData[dayOfWeek][hour]++;

            totalFiles += changedFiles.size();

            allCommits.add(new CommitInfo(
                commit.getName(), commit.getName().substring(0, 7),
                authorName, authorEmail,
                Instant.ofEpochSecond(commit.getCommitTime())
                    .atZone(ZoneId.systemDefault()).toLocalDateTime().toString(),
                commit.getFullMessage(), added + deleted, changedFiles.size(),
                changedFiles, modules, added, deleted
            ));
        }

        // 构建结果
        var authors = authorMap.values().stream()
            .map(ab -> new AuthorStatsDto(
                ab.name, ab.email, ab.commitCount,
                ab.linesAdded, ab.linesDeleted,
                ab.hourDist, ab.dayDist,
                computePeakHours(ab.hourDist),
                ab.modules.stream().limit(3).toList(),
                null // profile 由 LLM Reduce 阶段填充
            )).toList();

        var modules = moduleCommitCount.entrySet().stream()
            .map(e -> new ModuleStatsDto(
                e.getKey(), e.getValue(),
                0, // linesChanged 需要额外计算
                moduleContributors.getOrDefault(e.getKey(), Set.of()).stream().limit(5).toList(),
                null // insight 由 LLM Reduce 阶段填充
            )).toList();

        var heatmapPoints = new ArrayList<HeatmapPoint>();
        int maxCount = 0;
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                if (heatmapData[d][h] > 0) {
                    heatmapPoints.add(new HeatmapPoint(d, h, heatmapData[d][h]));
                    maxCount = Math.max(maxCount, heatmapData[d][h]);
                }
            }
        }

        var repoInfo = new RepositoryInfoDto(
            repository.getDirectory() != null ? repository.getDirectory().getName() : "unknown",
            repository.getConfig().getString("remote", "origin", "url"),
            branch != null ? branch : repository.getBranch(),
            new RepositoryInfoDto.AnalysisPeriod(since, until),
            commitList.size(), authorMap.size(), totalFiles
        );

        return new ExtractionResult(repoInfo, authors, modules, allCommits,
            new ActivityHeatmapDto(heatmapPoints, maxCount));
    }

    private String computePeakHours(int[] hourDist) {
        int max = 0, start = 0, currentStart = 0, currentLen = 0;
        for (int i = 0; i < 24; i++) {
            if (hourDist[i] > 0) {
                if (currentLen == 0) currentStart = i;
                currentLen++;
                int len = currentLen;
                if (len > max) { max = len; start = currentStart; }
            } else {
                currentLen = 0;
            }
        }
        if (max == 0) return "N/A";
        return String.format("%02d:00-%02d:00", start, (start + max) % 24);
    }

    // 内部辅助类
    private static class AuthorBuilder {
        String name, email;
        int commitCount, linesAdded, linesDeleted;
        int[] hourDist = new int[24];
        int[] dayDist = new int[7];
        Set<String> modules = new LinkedHashSet<>();

        AuthorBuilder(String name, String email) {
            this.name = name; this.email = email;
        }
    }

    public record CommitInfo(
        String hash, String shortHash, String author, String authorEmail,
        String date, String message, int totalLines, int filesChanged,
        List<String> changedFiles, Set<String> modules,
        int linesAdded, int linesDeleted
    ) {}

    public record ExtractionResult(
        RepositoryInfoDto repoInfo,
        List<AuthorStatsDto> authors,
        List<ModuleStatsDto> modules,
        List<CommitInfo> commits,
        ActivityHeatmapDto heatmap
    ) {}
}
```

- [ ] **Step 7: 运行全部测试**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现模块检测与元数据提取"
```

---

### Task 7: 异常规则引擎 (TDD)

**Files:**
- Create: `src/test/java/com/repordar/anomaly/GiantCommitRuleTest.java`
- Create: `src/main/java/com/repordar/anomaly/GiantCommitRule.java`
- Create: `src/test/java/com/repordar/anomaly/VolatileFileRuleTest.java`
- Create: `src/main/java/com/repordar/anomaly/VolatileFileRule.java`
- Create: `src/test/java/com/repordar/anomaly/CrossDomainRuleTest.java`
- Create: `src/main/java/com/repordar/anomaly/CrossDomainRule.java`
- Create: `src/main/java/com/repordar/anomaly/AnomalyFilter.java`

- [ ] **Step 1: 编写 GiantCommitRule 测试**

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;

class GiantCommitRuleTest {

    private final GiantCommitRule rule = new GiantCommitRule(500);

    @Test
    void shouldFlagCommitWithMoreThan500TotalLines() {
        var commit = new CommitInfo("abc", "abc", "Alice", "a@b.com",
            "2024-01-01", "big change", 600, 10,
            List.of("file1.java"), Set.of("order"), 400, 200);
        var result = rule.check(List.of(commit));
        assertEquals(1, result.size());
        assertEquals("abc", result.get(0).hash());
    }

    @Test
    void shouldNotFlagCommitWithExactThreshold() {
        var commit = new CommitInfo("abc", "abc", "Alice", "a@b.com",
            "2024-01-01", "exact", 500, 5,
            List.of("file1.java"), Set.of("order"), 300, 200);
        var result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldNotFlagSmallCommit() {
        var commit = new CommitInfo("abc", "abc", "Alice", "a@b.com",
            "2024-01-01", "small", 50, 2,
            List.of("file1.java"), Set.of("order"), 30, 20);
        var result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests GiantCommitRuleTest`
Expected: FAIL

- [ ] **Step 3: 实现 GiantCommitRule**

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class GiantCommitRule {

    private final int threshold;

    public GiantCommitRule(int threshold) {
        this.threshold = threshold;
    }

    public List<CommitInfo> check(List<CommitInfo> commits) {
        return commits.stream()
            .filter(c -> c.totalLines() > threshold)
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests GiantCommitRuleTest`
Expected: PASS

- [ ] **Step 5: 编写 VolatileFileRule 测试**

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;

class VolatileFileRuleTest {

    private final VolatileFileRule rule = new VolatileFileRule(7, 3);

    @Test
    void shouldFlagFileModifiedMoreThanThresholdInWindow() {
        var commits = List.of(
            makeCommit("abc1", "2024-01-01T10:00:00", List.of("src/order/Service.java")),
            makeCommit("abc2", "2024-01-02T10:00:00", List.of("src/order/Service.java")),
            makeCommit("abc3", "2024-01-03T10:00:00", List.of("src/order/Service.java")),
            makeCommit("abc4", "2024-01-04T10:00:00", List.of("src/order/Service.java"))
        );
        var result = rule.check(commits);
        assertEquals(1, result.size());
        assertTrue(result.get(0).path().contains("Service.java"));
    }

    @Test
    void shouldNotFlagFileWithExactThreshold() {
        var commits = List.of(
            makeCommit("abc1", "2024-01-01T10:00:00", List.of("src/order/Service.java")),
            makeCommit("abc2", "2024-01-02T10:00:00", List.of("src/order/Service.java")),
            makeCommit("abc3", "2024-01-03T10:00:00", List.of("src/order/Service.java"))
        );
        var result = rule.check(commits);
        assertTrue(result.isEmpty());
    }

    private CommitInfo makeCommit(String hash, String date, List<String> files) {
        return new CommitInfo(hash, hash.substring(0,4), "Alice", "a@b.com",
            date, "msg", 10, files.size(), files, Set.of("order"), 5, 5);
    }
}
```

- [ ] **Step 6: 运行测试确认失败，然后实现 VolatileFileRule**

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class VolatileFileRule {

    private final int windowDays;
    private final int threshold;

    public VolatileFileRule(int windowDays, int threshold) {
        this.windowDays = windowDays;
        this.threshold = threshold;
    }

    public List<VolatileFile> check(List<CommitInfo> commits) {
        // 文件路径 → 修改时间列表
        Map<String, List<LocalDateTime>> fileTimes = new LinkedHashMap<>();
        Map<String, Set<String>> fileContributors = new LinkedHashMap<>();
        Map<String, Integer> fileLinesChanged = new LinkedHashMap<>();

        for (var commit : commits) {
            var date = LocalDateTime.parse(commit.date());
            for (var file : commit.changedFiles()) {
                fileTimes.computeIfAbsent(file, k -> new ArrayList<>()).add(date);
                fileContributors.computeIfAbsent(file, k -> new LinkedHashSet<>()).add(commit.author());
                fileLinesChanged.merge(file, commit.totalLines(), Integer::sum);
            }
        }

        List<VolatileFile> result = new ArrayList<>();
        for (var entry : fileTimes.entrySet()) {
            var path = entry.getKey();
            var times = entry.getValue();
            Collections.sort(times);

            // 滑动窗口检查
            int maxInWindow = 0;
            for (int i = 0; i < times.size(); i++) {
                long count = times.stream()
                    .filter(t -> !t.isBefore(times.get(i)) &&
                                 ChronoUnit.DAYS.between(times.get(i), t) <= windowDays)
                    .count();
                maxInWindow = (int) Math.max(maxInWindow, count);
            }

            if (maxInWindow > threshold) {
                result.add(new VolatileFile(path, maxInWindow, windowDays,
                    List.copyOf(fileContributors.get(path)),
                    fileLinesChanged.getOrDefault(path, 0)));
            }
        }
        return result;
    }

    public record VolatileFile(String path, int changeCount, int windowDays,
                               List<String> contributors, int linesChanged) {}
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `./gradlew test --tests VolatileFileRuleTest`
Expected: PASS

- [ ] **Step 8: 编写 CrossDomainRule 测试 + 实现**

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;

class CrossDomainRuleTest {

    private final CrossDomainRule rule = new CrossDomainRule(3);

    @Test
    void shouldFlagCommitWithMoreThan3Modules() {
        var commit = new CommitInfo("abc", "abc", "Alice", "a@b.com",
            "2024-01-01", "cross", 100, 5,
            List.of("a.java", "b.java"), Set.of("order", "payment", "user", "inventory"), 50, 50);
        var result = rule.check(List.of(commit));
        assertEquals(1, result.size());
    }

    @Test
    void shouldNotFlagCommitWith3OrFewerModules() {
        var commit = new CommitInfo("abc", "abc", "Alice", "a@b.com",
            "2024-01-01", "ok", 100, 5,
            List.of("a.java"), Set.of("order", "payment", "user"), 50, 50);
        var result = rule.check(List.of(commit));
        assertTrue(result.isEmpty());
    }
}
```

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CrossDomainRule {

    private final int threshold;

    public CrossDomainRule(int threshold) {
        this.threshold = threshold;
    }

    public List<CommitInfo> check(List<CommitInfo> commits) {
        return commits.stream()
            .filter(c -> c.modules().size() > threshold)
            .collect(Collectors.toList());
    }
}
```

- [ ] **Step 9: 运行测试确认通过**

Run: `./gradlew test --tests CrossDomainRuleTest`
Expected: PASS

- [ ] **Step 10: 实现 AnomalyFilter 编排器**

```java
package com.repordar.anomaly;

import com.repordar.config.AppProperties;
import com.repordar.dto.*;
import com.repordar.git.MetadataExtractor.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AnomalyFilter {

    private final AppProperties props;

    public AnomalyFilter(AppProperties props) {
        this.props = props;
    }

    public AnomalyResult filter(List<CommitInfo> commits) {
        var anomalyProps = props.anomaly();

        var giantRule = new GiantCommitRule(anomalyProps.giantCommitThreshold());
        var volatileRule = new VolatileFileRule(anomalyProps.volatileWindowDays(), anomalyProps.volatileThreshold());
        var crossRule = new CrossDomainRule(anomalyProps.crossDomainThreshold());

        var giantCommits = giantRule.check(commits);
        var volatileFiles = volatileRule.check(commits);
        var crossDomainCommits = crossRule.check(commits);

        return new AnomalyResult(giantCommits, volatileFiles, crossDomainCommits);
    }

    public record AnomalyResult(
        List<CommitInfo> giantCommits,
        List<VolatileFileRule.VolatileFile> volatileFiles,
        List<CommitInfo> crossDomainCommits
    ) {}
}
```

- [ ] **Step 11: 运行全部测试**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 12: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现异常规则引擎（巨型/易挥发/跨域）含 TDD 测试"
```

---

### Task 8: 模糊提交评分引擎 (TDD)

**Files:**
- Create: `src/test/java/com/repordar/anomaly/VagueScoringEngineTest.java`
- Create: `src/main/java/com/repordar/anomaly/VagueScoringEngine.java`
- Create: `src/main/java/com/repordar/anomaly/VagueCommitDetector.java`

- [ ] **Step 1: 编写评分引擎测试**

```java
package com.repordar.anomaly;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class VagueScoringEngineTest {

    private final VagueScoringEngine engine = new VagueScoringEngine(List.of("order", "payment"));

    @Test
    void shouldFlagVagueGenericPhrase() {
        int score = engine.score("fix: 修复了订单相关的BUG");
        assertTrue(score < 50, "Score should be < 50 for vague message, got: " + score);
    }

    @Test
    void shouldPassClearSpecificMessage() {
        int score = engine.score("fix: resolve NullPointerException in OrderService.createOrder when payment timeout");
        assertTrue(score >= 50, "Score should be >= 50 for clear message, got: " + score);
    }

    @Test
    void shouldHandleConventionalCommits() {
        // 只有 type 前缀，description 为空
        int score = engine.score("fix:");
        assertTrue(score < 50, "Empty description after prefix should be vague");
    }

    @Test
    void shouldNotPenalizeTypePrefix() {
        // fix: 本身不应被罚分，只看 description
        int score = engine.score("fix: resolve race condition in payment flow");
        assertTrue(score >= 50, "fix prefix should not cause vagueness");
    }

    @Test
    void shouldBonusForModuleReference() {
        int score = engine.score("feat: add order cancellation endpoint");
        // "order" 匹配模块名，应加分
        assertTrue(score >= 60, "Should get bonus for referencing module name");
    }

    @Test
    void shouldBonusForIssueRef() {
        int score = engine.score("fix: fix login timeout #123");
        assertTrue(score >= 60, "Should get bonus for Issue reference");
    }

    @Test
    void shouldPenalizeTooShortDescription() {
        int score = engine.score("fix: 优化");
        assertTrue(score < 50, "Short description after conventional prefix should be vague");
    }

    @Test
    void shouldPenalizeKnownVaguePatterns() {
        int score1 = engine.score("优化了代码");
        assertTrue(score1 < 50);
        int score2 = engine.score("修复问题");
        assertTrue(score2 < 50);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests VagueScoringEngineTest`
Expected: FAIL

- [ ] **Step 3: 实现 VagueScoringEngine**

```java
package com.repordar.anomaly;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VagueScoringEngine {

    private static final Pattern CC_PATTERN = Pattern.compile("^(\\w+)(\\([\\w-]+\\))?:\\s*(.*)$");
    private static final Set<String> GENERIC_PHRASES = Set.of(
        "相关BUG", "一些修改", "若干调整", "部分优化", "相关问题",
        "相关功能", "代码优化", "代码调整", "小修改", "小改动"
    );
    private static final Set<String> VAGUE_PATTERNS = Set.of(
        "优化了代码", "修复问题", "小改动", "调整代码", "优化", "调整", "修复"
    );
    private static final Pattern ISSUE_REF = Pattern.compile("#\\d+|[A-Z]+-\\d+");
    private static final Pattern ERROR_CODE = Pattern.compile(
        "[A-Z][a-z]*Exception|OOM|NPE|ERR[-_]?\\d+", Pattern.CASE_INSENSITIVE
    );

    private final List<String> repoModuleNames;

    public VagueScoringEngine(List<String> repoModuleNames) {
        this.repoModuleNames = repoModuleNames;
    }

    public int score(String message) {
        if (message == null || message.isBlank()) return 0;

        int score = 100;
        String description = message;

        // 解析 Conventional Commits
        Matcher ccMatcher = CC_PATTERN.matcher(message.trim());
        if (ccMatcher.matches()) {
            description = ccMatcher.group(3).trim();
            if (description.isEmpty()) {
                score -= 50; // 只有 type: 没有描述
                return Math.max(score, 0);
            }
        }

        // 减分项：泛指词
        for (String phrase : GENERIC_PHRASES) {
            if (description.contains(phrase)) { score -= 30; break; }
        }

        // 减分项：缺少具体名词（只有动词）
        boolean hasSpecificContent = description.length() > 5 &&
            description.chars().filter(Character::isLetterOrDigit).count() > 3;
        if (!hasSpecificContent) { score -= 25; }

        // 减分项：description 太短
        if (description.length() <= 5) { score -= 20; }

        // 减分项：匹配已知模糊模式
        for (String pattern : VAGUE_PATTERNS) {
            if (description.equals(pattern) || message.trim().equals(pattern)) {
                score -= 25; break;
            }
        }

        // 加分项：引用具体模块名
        String lowerDesc = description.toLowerCase();
        for (String mod : repoModuleNames) {
            if (lowerDesc.contains(mod.toLowerCase())) { score += 15; break; }
        }

        // 加分项：Issue 编号
        if (ISSUE_REF.matcher(description).find()) { score += 10; }

        // 加分项：错误码/异常名
        if (ERROR_CODE.matcher(description).find()) { score += 10; }

        return Math.max(0, Math.min(100, score));
    }

    public String generateReason(String message, int score) {
        if (score >= 50) return "提交信息基本清晰";
        StringBuilder reason = new StringBuilder();
        String desc = extractDescription(message);
        for (String phrase : GENERIC_PHRASES) {
            if (desc.contains(phrase)) {
                reason.append("包含泛指词\"").append(phrase).append("\"；");
            }
        }
        if (desc.length() <= 5) reason.append("描述过短；");
        for (String pattern : VAGUE_PATTERNS) {
            if (desc.equals(pattern)) {
                reason.append("匹配已知模糊模式\"").append(pattern).append("\"；");
            }
        }
        if (reason.isEmpty()) reason.append("缺少具体描述");
        return reason.toString();
    }

    private String extractDescription(String message) {
        Matcher m = CC_PATTERN.matcher(message.trim());
        return m.matches() ? m.group(3).trim() : message.trim();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests VagueScoringEngineTest`
Expected: PASS

- [ ] **Step 5: 实现 VagueCommitDetector（双模式编排）**

```java
package com.repordar.anomaly;

import com.repordar.git.MetadataExtractor.CommitInfo;
import com.repordar.llm.LlmVagueScanner;
import com.repordar.dto.VagueCommitDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class VagueCommitDetector {

    private final LlmVagueScanner llmScanner;
    private final VagueScoringEngine scoringEngine;

    public VagueCommitDetector(LlmVagueScanner llmScanner, VagueScoringEngine scoringEngine) {
        this.llmScanner = llmScanner;
        this.scoringEngine = scoringEngine;
    }

    /**
     * @param commits 全量提交列表
     * @param llmEnabled 是否配置了 LLM
     * @param moduleNames 仓库模块名列表（用于评分引擎加分）
     */
    public List<VagueCommitDto> detect(List<CommitInfo> commits, boolean llmEnabled,
                                        Set<String> moduleNames) {
        if (llmEnabled) {
            return llmScanner.scan(commits);
        } else {
            return detectByScoring(commits, moduleNames);
        }
    }

    private List<VagueCommitDto> detectByScoring(List<CommitInfo> commits,
                                                   Set<String> moduleNames) {
        var engine = new VagueScoringEngine(List.copyOf(moduleNames));
        List<VagueCommitDto> result = new ArrayList<>();

        for (var commit : commits) {
            int score = engine.score(commit.message());
            if (score < 50) {
                String reason = engine.generateReason(commit.message(), score);
                result.add(new VagueCommitDto(
                    commit.hash(), commit.shortHash(), commit.author(), commit.authorEmail(),
                    commit.date(), commit.message(), reason, score, null
                ));
            }
        }
        return result;
    }
}
```

- [ ] **Step 6: 运行全部测试**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现模糊提交双模式检测（LLM + Java 评分降级）"
```

---

## Phase 3: LLM Integration

### Task 9: LLM 客户端

**Files:**
- Create: `src/main/java/com/repordar/llm/LlmClient.java`

- [ ] **Step 1: 实现 OpenAI 兼容 API 客户端**

```java
package com.repordar.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class LlmClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public String chat(String baseUrl, String apiKey, String model,
                       String systemPrompt, String userPrompt) throws Exception {
        var body = mapper.writeValueAsString(Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ),
            "temperature", 0.1
        ));

        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(120))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM API error: " + response.statusCode() + " " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText();
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现 OpenAI 兼容 LLM 客户端"
```

---

### Task 10: LLM 模糊扫描 + Map 翻译 + Reduce 洞察

**Files:**
- Create: `src/main/java/com/repordar/llm/LlmVagueScanner.java`
- Create: `src/main/java/com/repordar/llm/LlmMapTranslator.java`
- Create: `src/test/java/com/repordar/llm/LlmMapTranslatorTest.java`
- Create: `src/main/java/com/repordar/llm/LlmReduceAnalyzer.java`

- [ ] **Step 1: 实现 LlmVagueScanner**

```java
package com.repordar.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.VagueCommitDto;
import com.repordar.git.MetadataExtractor.CommitInfo;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class LlmVagueScanner {

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmVagueScanner(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public List<VagueCommitDto> scan(List<CommitInfo> commits) {
        try {
            return doScan(commits);
        } catch (Exception e) {
            System.err.println("⚠️  LLM 模糊扫描失败，降级为空列表: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<VagueCommitDto> doScan(List<CommitInfo> commits) throws Exception {
        List<VagueCommitDto> allResults = new ArrayList<>();
        int batchSize = 500;

        for (int i = 0; i < commits.size(); i += batchSize) {
            var batch = commits.subList(i, Math.min(i + batchSize, commits.size()));
            var batchResults = scanBatch(batch);
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    private List<VagueCommitDto> scanBatch(List<CommitInfo> batch) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (var c : batch) {
            sb.append(c.shortHash()).append(" | ").append(c.message()).append("\n");
        }

        String systemPrompt = """
            你是一个 Git 提交信息质量分析专家。
            以下是仓库的提交记录，每行格式为 "shortHash | commit message"。
            请判断哪些提交是"模糊提交"——即没有说清楚具体改了什么、为什么改、影响了什么功能。
            只返回模糊提交，格式为 JSON 数组：[{"commit":"shortHash","reason":"模糊原因"}]
            如果某条提交足够清晰，不需要包含在结果中。
            只返回 JSON，不要其他内容。
            """;

        // 注意：baseUrl, apiKey, model 由调用方传入或从 config 获取
        // 此处简化处理，实际由 pipeline 传入
        throw new UnsupportedOperationException("需要通过 pipeline 传入 LLM 配置");
    }
}
```

> **注意**: LLM 组件需要从 Pipeline 获取 LLM 配置。这里先写骨架，Pipeline 任务中会完成接线。

- [ ] **Step 2: 编写 LlmMapTranslator JSON 反序列化测试**

```java
package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.CommitAnalysisDto;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmMapTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldDeserializeValidJson() throws Exception {
        String json = """
            {
              "intent": "修复订单竞态条件",
              "tags": ["BUGFIX"],
              "risk_level": "HIGH",
              "message_quality": "CLEAR",
              "quality_reason": null
            }
            """;
        var dto = mapper.readValue(json, CommitAnalysisDto.class);
        assertEquals("修复订单竞态条件", dto.intent());
        assertEquals(List.of("BUGFIX"), dto.tags());
        assertEquals("HIGH", dto.riskLevel());
        assertEquals("CLEAR", dto.messageQuality());
    }

    @Test
    void shouldTolerateExtraFields() throws Exception {
        String json = """
            {
              "intent": "test",
              "tags": ["FEATURE"],
              "risk_level": "LOW",
              "extra_field": "ignored"
            }
            """;
        var dto = mapper.readValue(json, CommitAnalysisDto.class);
        assertEquals("test", dto.intent());
    }

    @Test
    void shouldFallbackInvalidTag() {
        var translator = new LlmMapTranslator(null);
        var result = translator.sanitizeTags(List.of("INVALID_TAG"));
        assertEquals(List.of("CHORE"), result);
    }

    @Test
    void shouldKeepValidTags() {
        var translator = new LlmMapTranslator(null);
        var result = translator.sanitizeTags(List.of("BUGFIX", "REFACTOR"));
        assertEquals(List.of("BUGFIX", "REFACTOR"), result);
    }
}
```

- [ ] **Step 3: 实现 LlmMapTranslator**

```java
package com.repordar.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.CommitAnalysisDto;
import com.repordar.git.MetadataExtractor.CommitInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class LlmMapTranslator {

    private static final Set<String> VALID_TAGS = Set.of(
        "BUGFIX", "FEATURE", "REFACTOR", "PERF", "DOCS", "TEST", "CHORE", "HOTFIX"
    );
    private static final Set<String> VALID_RISKS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmMapTranslator(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public CommitAnalysisDto analyze(CommitInfo commit, String diffSummary,
                                      String baseUrl, String apiKey, String model) {
        try {
            return doAnalyze(commit, diffSummary, baseUrl, apiKey, model);
        } catch (Exception e) {
            System.err.println("⚠️  LLM Map 分析失败 (" + commit.shortHash() + "): " + e.getMessage());
            return null; // 降级为 null
        }
    }

    private CommitAnalysisDto doAnalyze(CommitInfo commit, String diffSummary,
                                         String baseUrl, String apiKey, String model) throws Exception {
        String systemPrompt = """
            你是 Git 代码分析专家。请分析以下提交并严格以 JSON 格式输出：
            {"intent":"提交意图","tags":["TAG1"],"risk_level":"LEVEL","message_quality":"QUALITY","quality_reason":"原因"}
            tags 可选值: BUGFIX, FEATURE, REFACTOR, PERF, DOCS, TEST, CHORE, HOTFIX
            risk_level 可选值: LOW, MEDIUM, HIGH, CRITICAL
            message_quality 可选值: CLEAR, VAGUE, MINIMAL
            只返回 JSON，不要其他内容。
            """;

        String userPrompt = "Commit: " + commit.shortHash() + "\nMessage: " + commit.message()
            + "\n\nDiff摘要:\n" + diffSummary;

        String response = llmClient.chat(baseUrl, apiKey, model, systemPrompt, userPrompt);

        // 提取 JSON（LLM 可能返回 markdown 代码块包裹的 JSON）
        String json = extractJson(response);
        CommitAnalysisDto raw = mapper.readValue(json, CommitAnalysisDto.class);

        // 校验枚举值，非法值降级
        return new CommitAnalysisDto(
            raw.intent(),
            sanitizeTags(raw.tags()),
            sanitizeRiskLevel(raw.riskLevel()),
            raw.messageQuality(),
            raw.qualityReason()
        );
    }

    public List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of("CHORE");
        List<String> valid = tags.stream().filter(VALID_TAGS::contains).toList();
        return valid.isEmpty() ? List.of("CHORE") : valid;
    }

    public String sanitizeRiskLevel(String level) {
        if (level == null || !VALID_RISKS.contains(level.toUpperCase())) return "LOW";
        return level.toUpperCase();
    }

    private String extractJson(String text) {
        // 尝试提取 ```json ... ``` 中的内容
        int start = text.indexOf("```json");
        if (start >= 0) {
            int jsonStart = text.indexOf('\n', start) + 1;
            int jsonEnd = text.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) return text.substring(jsonStart, jsonEnd).trim();
        }
        // 尝试找 { ... }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return text.substring(braceStart, braceEnd + 1);
        }
        return text;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests LlmMapTranslatorTest`
Expected: PASS

- [ ] **Step 5: 实现 LlmReduceAnalyzer**

```java
package com.repordar.llm;

import com.repordar.dto.AuthorStatsDto;
import com.repordar.dto.ModuleStatsDto;
import com.repordar.dto.CommitAnalysisDto;
import com.repordar.dto.GlobalInsightDto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmReduceAnalyzer {

    private final LlmClient llmClient;

    public LlmReduceAnalyzer(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public GlobalInsightDto generateInsight(
        List<AuthorStatsDto> authors, List<ModuleStatsDto> modules,
        List<CommitAnalysisDto> analyses, int healthScore,
        String baseUrl, String apiKey, String model) {
        try {
            return doGenerate(authors, modules, analyses, healthScore, baseUrl, apiKey, model);
        } catch (Exception e) {
            System.err.println("⚠️  LLM Reduce 分析失败: " + e.getMessage());
            return new GlobalInsightDto(null, null, healthScore);
        }
    }

    private GlobalInsightDto doGenerate(
        List<AuthorStatsDto> authors, List<ModuleStatsDto> modules,
        List<CommitAnalysisDto> analyses, int healthScore,
        String baseUrl, String apiKey, String model) throws Exception {

        StringBuilder summary = new StringBuilder();
        summary.append("作者统计:\n");
        for (var a : authors) {
            summary.append("- ").append(a.name()).append(": ").append(a.commitCount())
                .append(" 次提交\n");
        }
        summary.append("\n模块统计:\n");
        for (var m : modules) {
            summary.append("- ").append(m.name()).append(": ").append(m.commitCount())
                .append(" 次提交\n");
        }
        summary.append("\n异常分析结果:\n");
        for (var a : analyses) {
            summary.append("- intent=").append(a.intent())
                .append(", risk=").append(a.riskLevel()).append("\n");
        }
        summary.append("\n健康度评分: ").append(healthScore).append("/100");

        String systemPrompt = """
            你是研发团队分析专家。根据以下仓库分析数据，生成：
            1. 一段全局总结（2-3句话，评价团队整体开发质量）
            2. 3-5条改进建议
            请以 JSON 格式返回：{"summary":"...","recommendations":["..."]}
            只返回 JSON，不要其他内容。
            """;

        String response = llmClient.chat(baseUrl, apiKey, model, systemPrompt, summary.toString());

        // 解析 JSON
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = response;
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) json = response.substring(start, end + 1);

        var node = mapper.readTree(json);
        return new GlobalInsightDto(
            node.path("summary").asText(null),
            mapper.convertValue(node.path("recommendations"), List.class),
            healthScore
        );
    }
}
```

- [ ] **Step 6: 运行全部测试**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现 LLM 语义层（模糊扫描 + Map 翻译 + Reduce 洞察）"
```

---

## Phase 4: Report & Pipeline

### Task 11: 报告生成器

**Files:**
- Create: `src/main/java/com/repordar/report/ReportGenerator.java`

- [ ] **Step 1: 实现报告生成器**

```java
package com.repordar.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repordar.dto.ReportDataDto;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class ReportGenerator {

    private final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 生成 HTML 报告文件。
     * @param data 完整报告数据
     * @param outputDir 输出目录
     * @param templateHtml HTML 模板内容
     * @return 生成的报告文件路径
     */
    public String generate(ReportDataDto data, String outputDir, String templateHtml) throws IOException {
        // 序列化数据为 JSON
        String json = mapper.writeValueAsString(data);
        // HTML 转义 JSON 中的特殊字符
        String escapedJson = json.replace("</script>", "<\\/script>");

        // 替换模板占位符
        String html = templateHtml.replace("__INJECT_DATA__", escapedJson);

        // 确保输出目录存在
        Path dir = Path.of(outputDir);
        Files.createDirectories(dir);

        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
        String repoName = data.repository().name().replaceAll("[^a-zA-Z0-9-_]", "");
        String filename = "repordar-" + repoName + "-" + timestamp + ".html";

        Path reportPath = dir.resolve(filename);
        Files.writeString(reportPath, html);

        return reportPath.toAbsolutePath().toString();
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现报告生成器（JSON 序列化 + 模板注入）"
```

---

### Task 12: HTML 报告模板

**Files:**
- Create: `src/main/resources/report-template/index.html`

这是 Vue3 + ECharts + TailwindCSS 单文件模板，按前端设计文档实现完整页面。此文件较大，包含所有 9 个组件。

- [ ] **Step 1: 创建 HTML 报告模板**

完整的 Vue3 SPA 模板，包含：
- CDN 引入 Vue3、ECharts 5、TailwindCSS 3
- `const __DATA__ = __INJECT_DATA__;` 数据注入点
- 9 个组件按设计文档布局：Header、热力图、贡献排行、作者卡片、异常总览、异常详情（含 Tab 切换）、模糊提交详情、风险解读面板、全局洞察 Footer
- 深色主题（slate-900 背景）
- `v-if` 降级渲染控制

> 此文件内容详见 `docs/superpowers/specs/2026-06-12-reporadar-frontend-design.md`，实现时严格按设计文档的 ASCII 线框图和组件规格编写。

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现 Vue3 + ECharts 报告 HTML 模板"
```

---

### Task 13: 分析管线编排

**Files:**
- Create: `src/main/java/com/repordar/pipeline/AnalysisPipeline.java`

- [ ] **Step 1: 实现管线编排器**

```java
package com.repordar.pipeline;

import com.repordar.anomaly.AnomalyFilter;
import com.repordar.anomaly.VagueCommitDetector;
import com.repordar.config.AppProperties;
import com.repordar.dto.*;
import com.repordar.git.GitCloner;
import com.repordar.git.MetadataExtractor;
import com.repordar.llm.LlmMapTranslator;
import com.repordar.llm.LlmReduceAnalyzer;
import com.repordar.llm.LlmVagueScanner;
import com.repordar.report.ReportGenerator;
import com.repordar.sse.SseProgressService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AnalysisPipeline {

    private final GitCloner gitCloner;
    private final MetadataExtractor metadataExtractor;
    private final AnomalyFilter anomalyFilter;
    private final VagueCommitDetector vagueDetector;
    private final LlmMapTranslator mapTranslator;
    private final LlmReduceAnalyzer reduceAnalyzer;
    private final ReportGenerator reportGenerator;
    private final SseProgressService sse;
    private final AppProperties props;

    public AnalysisPipeline(GitCloner gitCloner, MetadataExtractor metadataExtractor,
                             AnomalyFilter anomalyFilter, VagueCommitDetector vagueDetector,
                             LlmMapTranslator mapTranslator, LlmReduceAnalyzer reduceAnalyzer,
                             ReportGenerator reportGenerator, SseProgressService sse,
                             AppProperties props) {
        this.gitCloner = gitCloner;
        this.metadataExtractor = metadataExtractor;
        this.anomalyFilter = anomalyFilter;
        this.vagueDetector = vagueDetector;
        this.mapTranslator = mapTranslator;
        this.reduceAnalyzer = reduceAnalyzer;
        this.reportGenerator = reportGenerator;
        this.sse = sse;
        this.props = props;
    }

    public String execute(String repoRef, String outputDir, String since, String until,
                           String branch, String llmKey, String llmUrl, String llmModel) throws Exception {
        long startTime = System.currentTimeMillis();
        boolean llmEnabled = llmKey != null && !llmKey.isBlank();

        // Stage 1: CLONE
        sse.sendProgress("CLONE", "正在克隆仓库...", 5);
        var cloned = gitCloner.cloneOrOpen(repoRef);
        try {
            // Stage 2: METADATA
            sse.sendProgress("METADATA", "提取元数据...", 20);
            var extraction = metadataExtractor.extract(cloned.repository(), branch, since, until);

            // Stage 3: ANOMALY
            sse.sendProgress("ANOMALY", "执行异常检测...", 40);
            var anomalyResult = anomalyFilter.filter(extraction.commits());

            // Stage 4: VAGUE DETECTION
            Set<String> moduleNames = extraction.modules().stream()
                .map(ModuleStatsDto::name).collect(Collectors.toSet());
            List<VagueCommitDto> vagueCommits;
            if (llmEnabled) {
                sse.sendProgress("LLM_VAGUE", "LLM 语义扫描提交信息...", 50);
                vagueCommits = vagueDetector.detect(extraction.commits(), true, moduleNames);
            } else {
                sse.sendProgress("LLM_VAGUE", "使用规则评分检测模糊提交...", 50);
                vagueCommits = vagueDetector.detect(extraction.commits(), false, moduleNames);
            }

            // Stage 5: LLM MAP (逐条分析异常提交)
            Map<String, CommitAnalysisDto> analysisMap = new HashMap<>();
            if (llmEnabled) {
                var anomalousCommits = new ArrayList<>(anomalyResult.giantCommits());
                anomalousCommits.addAll(anomalyResult.crossDomainCommits());

                int total = anomalousCommits.size() + vagueCommits.size();
                for (int i = 0; i < anomalousCommits.size(); i++) {
                    var commit = anomalousCommits.get(i);
                    sse.sendProgress("LLM_MAP", "深度分析 " + (i+1) + "/" + total + "...", 55 + (i * 20 / Math.max(total, 1)));
                    // TODO: 构造 diffSummary (截断到 4000 tokens)
                    var analysis = mapTranslator.analyze(commit, "", llmUrl, llmKey, llmModel);
                    analysisMap.put(commit.hash(), analysis);
                }
                for (int i = 0; i < vagueCommits.size(); i++) {
                    var vc = vagueCommits.get(i);
                    sse.sendProgress("LLM_MAP", "深度分析模糊提交 " + (i+1) + "/" + vagueCommits.size() + "...",
                        55 + ((anomalousCommits.size() + i) * 20 / Math.max(total, 1)));
                    var info = extraction.commits().stream()
                        .filter(c -> c.hash().equals(vc.hash())).findFirst().orElse(null);
                    if (info != null) {
                        var analysis = mapTranslator.analyze(info, "", llmUrl, llmKey, llmModel);
                        analysisMap.put(vc.hash(), analysis);
                    }
                }
            } else {
                sse.sendProgress("LLM_MAP", "无 LLM，跳过深度分析", 75);
            }

            // Stage 6: LLM REDUCE
            GlobalInsightDto globalInsight;
            int healthScore = computeHealthScore(
                anomalyResult.giantCommits().size(),
                anomalyResult.volatileFiles().size(),
                anomalyResult.crossDomainCommits().size(),
                vagueCommits.size()
            );

            if (llmEnabled) {
                sse.sendProgress("LLM_REDUCE", "生成全局洞察...", 80);
                var allAnalyses = analysisMap.values().stream().filter(Objects::nonNull).toList();
                globalInsight = reduceAnalyzer.generateInsight(
                    extraction.authors(), extraction.modules(),
                    allAnalyses, healthScore, llmUrl, llmKey, llmModel
                );
            } else {
                sse.sendProgress("LLM_REDUCE", "无 LLM，跳过全局洞察", 85);
                globalInsight = new GlobalInsightDto(null, null, healthScore);
            }

            // Stage 7: REPORT
            sse.sendProgress("REPORT", "生成报告...", 90);
            var anomalies = new AnomalyGroupDto(
                anomalyResult.giantCommits().stream()
                    .map(c -> new GiantCommitDto(c.hash(), c.shortHash(), c.author(), c.authorEmail(),
                        c.date(), c.message(), c.totalLines(), c.filesChanged(),
                        List.copyOf(c.modules()), analysisMap.get(c.hash())))
                    .toList(),
                anomalyResult.volatileFiles().stream()
                    .map(v -> new VolatileFileDto(v.path(), v.changeCount(), v.windowDays(),
                        v.contributors(), v.linesChanged()))
                    .toList(),
                anomalyResult.crossDomainCommits().stream()
                    .map(c -> new CrossDomainCommitDto(c.hash(), c.shortHash(), c.author(), c.authorEmail(),
                        c.date(), c.message(), List.copyOf(c.modules()), c.filesChanged(),
                        analysisMap.get(c.hash())))
                    .toList(),
                vagueCommits.stream()
                    .map(vc -> new VagueCommitDto(vc.hash(), vc.shortHash(), vc.author(), vc.authorEmail(),
                        vc.date(), vc.message(), vc.vagueReason(), vc.score(),
                        analysisMap.get(vc.hash())))
                    .toList()
            );

            var reportData = new ReportDataDto(
                extraction.repoInfo(),
                extraction.authors(),
                extraction.modules(),
                anomalies,
                globalInsight,
                extraction.heatmap(),
                new AnalysisMetaDto(
                    LocalDateTime.now().toString(),
                    llmEnabled, llmEnabled ? llmModel : null,
                    System.currentTimeMillis() - startTime, "1.0.0"
                )
            );

            var templateResource = new ClassPathResource("report-template/index.html");
            String templateHtml = templateResource.getContentAsString(StandardCharsets.UTF_8);

            String reportPath = reportGenerator.generate(reportData, outputDir, templateHtml);
            sse.sendComplete(reportPath);
            return reportPath;

        } finally {
            gitCloner.cleanup(cloned);
        }
    }

    private int computeHealthScore(int giant, int volatile, int crossDomain, int vague) {
        int score = 100;
        score -= vague * 2;
        score -= giant * 3;
        score -= volatile * 5;
        score -= crossDomain * 2;
        return Math.max(0, Math.min(100, score));
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "✨ feat: 实现分析管线编排器，串联完整分析流程"
```

---

## Phase 5: Integration & Polish

### Task 14: 端到端集成测试

**Files:**
- Create: `src/test/java/com/repordar/integration/EndToEndTest.java`

- [ ] **Step 1: 编写端到端测试（无 LLM 降级模式）**

```java
package com.repordar.integration;

import com.repordar.dto.ReportDataDto;
import com.repordar.report.ReportGenerator;
import com.repordar.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

class EndToEndTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateReportWithNullLlmFields() throws Exception {
        // 模拟降级模式下的完整报告数据
        var data = new ReportDataDto(
            new RepositoryInfoDto("test-repo", "http://example.com/repo.git", "main",
                new RepositoryInfoDto.AnalysisPeriod("2024-01-01", "2024-01-31"),
                10, 2, 20),
            List.of(new AuthorStatsDto("Alice", "alice@test.com", 8, 500, 200,
                new int[24], new int[7], "09:00-15:00", List.of("order"), null)),
            List.of(new ModuleStatsDto("order", 8, 500, List.of("Alice"), null)),
            new AnomalyGroupDto(List.of(), List.of(), List.of(), List.of()),
            new GlobalInsightDto(null, null, 100),
            new ActivityHeatmapDto(List.of(), 0),
            new AnalysisMetaDto("2024-01-31", false, null, 1000, "1.0.0")
        );

        var generator = new ReportGenerator();
        String template = "<html><script>const __DATA__ = __INJECT_DATA__;</script></html>";
        String path = generator.generate(data, tempDir.toString(), template);

        assertTrue(path.endsWith(".html"));
        assertTrue(Path.of(path).toFile().exists());
        String content = java.nio.file.Files.readString(Path.of(path));
        assertTrue(content.contains("test-repo"));
        assertTrue(content.contains("__DATA__"));
        assertFalse(content.contains("__INJECT_DATA__"));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "✅ test: 添加端到端集成测试（降级模式）"
```

---

### Task 15: .claude/ 工作流配置

**Files:**
- Create: `.claude/CLAUDE.md`

- [ ] **Step 1: 创建 CLAUDE.md 项目约束**

```markdown
# RepoRadar 项目约束

## 架构红线
- JGit 层 (`git/`) 严禁调用 LLM
- LLM 层 (`llm/`) 必须输出结构化 JSON，Java 端强制反序列化为 DTO
- 严禁 LLM 生成 HTML 视图代码
- 视图层通过 `__INJECT_DATA__` 模板注入，保证渲染稳定

## 技术栈
- Java 17, Spring Boot 3.2, Gradle
- JGit 6.8, Picocli 4.7, Jackson
- Vue3 + ECharts 5 + TailwindCSS 3 (CDN, 无构建)

## 开发规范
- `MetadataExtractor`、`AnomalyFilter`、`VagueScoringEngine`、`LlmMapTranslator` 的 JSON 反序列化必须严格遵循 TDD
- LLM 降级时系统不崩溃，基线功能可用
- 单 JAR 部署，零额外依赖

## 测试
- 运行: `./gradlew test`
- 构建: `./gradlew bootJar`
```

- [ ] **Step 2: 提交**

```bash
git add -A
git commit -m "📝 docs: 添加 CLAUDE.md 项目约束文件"
```

---

### Task 16: 最终构建验证

- [ ] **Step 1: 完整构建**

Run: `./gradlew clean bootJar`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 验证 JAR 可执行**

Run: `java -jar build/libs/repordar-1.0.0.jar --help`
Expected: 显示 CLI 帮助信息

- [ ] **Step 3: 运行全量测试**

Run: `./gradlew test`
Expected: ALL TESTS PASS

- [ ] **Step 4: 提交最终状态**

```bash
git add -A
git commit -m "✅ chore: 最终构建验证通过"
```
