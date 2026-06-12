# RepoRadar 接口设计文档

> 定义 Java 分析引擎与 Vue3 前端之间的数据契约。
> 日期：2026-06-12

---

## 1. 数据注入机制

Java 将全部分析结果序列化为 JSON，替换 HTML 模板中的 `__INJECT_DATA__` 占位符：

```html
<script>
  const __DATA__ = __INJECT_DATA__;
</script>
```

Vue3 启动时读取 `window.__DATA__`，驱动全部渲染。**这是 Java 与 Vue3 之间的唯一数据通道，无 REST API。**

---

## 2. SSE 事件接口

Spring Boot 内嵌 HTTP Server 通过 `SseEmitter` 推送管线进度。

### 2.1 事件格式

```
event: progress
data: {"stage":"CLONE","message":"正在克隆仓库...","percent":10}
```

### 2.2 阶段枚举与进度分配

| 阶段 | 枚举值 | 进度范围 | 说明 |
|---|---|---|---|
| 克隆仓库 | `CLONE` | 0-10% | git clone --bare |
| 提取元数据 | `METADATA` | 10-35% | JGit 遍历提交 |
| 异常过滤 | `ANOMALY` | 35-45% | 规则引擎判定 |
| 模糊提交扫描 | `LLM_VAGUE` | 45-55% | LLM 批量扫描（无 LLM 时跳过） |
| 逐条深度分析 | `LLM_MAP` | 55-75% | LLM Map 阶段（无 LLM 时跳过） |
| 全局洞察生成 | `LLM_REDUCE` | 75-85% | LLM Reduce 阶段（无 LLM 时跳过） |
| 报告生成 | `REPORT` | 85-100% | 序列化 + 模板注入 |

### 2.3 SSE 事件 DTO

```java
public record SseEvent(
    String stage,    // 阶段枚举值
    String message,  // 人类可读的进度描述
    int percent      // 0-100
) {}
```

### 2.4 终止事件

```
event: complete
data: {"reportPath": "reports/reporadar-2024-01-25-143000.html"}

event: error
data: {"stage": "CLONE", "message": "仓库地址无法访问"}
```

---

## 3. 核心数据契约 (`__INJECT_DATA__` JSON Schema)

### 3.1 顶层结构

```json
{
  "repository": { },
  "authors": [ ],
  "modules": [ ],
  "anomalies": { },
  "globalInsight": { },
  "activityHeatmap": { },
  "meta": { }
}
```

### 3.2 `repository` — 仓库基础信息

```json
{
  "name": "my-project",
  "url": "https://github.com/org/my-project.git",
  "branch": "main",
  "analysisPeriod": {
    "since": "2024-01-01",
    "until": "2024-01-31"
  },
  "totalCommits": 1234,
  "totalAuthors": 12,
  "totalFiles": 456
}
```

### 3.3 `authors[]` — 作者统计

```json
[
  {
    "name": "Alice",
    "email": "alice@example.com",
    "commitCount": 120,
    "linesAdded": 5000,
    "linesDeleted": 2000,
    "activeHourDistribution": [0, 0, 0, 0, 0, 0, 0, 1, 5, 15, 20, 25, 18, 22, 30, 28, 15, 10, 3, 1, 0, 0, 0, 0],
    "activeDayDistribution": [15, 22, 18, 20, 25, 12, 8],
    "peakHours": "09:00-15:00",
    "primaryModules": ["order", "payment"],
    "profile": "Alice 是订单和支付模块的核心贡献者，提交集中在工作日上午..."
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `activeHourDistribution` | `int[24]` | 每小时提交数（0-23） |
| `activeDayDistribution` | `int[7]` | 每天提交数（周一=0 到 周日=6） |
| `primaryModules` | `string[]` | 贡献最多的模块（取 top 3） |
| `profile` | `string?` | LLM 生成的作者画像（降级模式下为 null） |

### 3.4 `modules[]` — 模块统计

```json
[
  {
    "name": "order",
    "commitCount": 50,
    "linesChanged": 3000,
    "topContributors": ["Alice", "Bob"],
    "insight": "订单模块近期变更频繁，主要集中在支付流程重构..."
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `insight` | `string?` | LLM 生成的模块洞察（降级模式下为 null） |

### 3.5 `anomalies` — 异常检测分组

```json
{
  "giantCommits": [ ],
  "volatileFiles": [ ],
  "crossDomainCommits": [ ],
  "vagueCommits": [ ]
}
```

#### 3.5.1 `giantCommits[]` — 巨型提交

```json
[
  {
    "hash": "abc1234",
    "shortHash": "abc1",
    "author": "Alice",
    "authorEmail": "alice@example.com",
    "date": "2024-01-15T14:30:00",
    "message": "refactor: rewrite payment flow",
    "totalLines": 823,
    "filesChanged": 25,
    "modules": ["order", "payment"],
    "analysis": {
      "intent": "重写支付流程以解决并发竞态条件",
      "tags": ["REFACTOR", "BUGFIX"],
      "riskLevel": "HIGH",
      "messageQuality": "CLEAR",
      "qualityReason": null
    }
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `analysis` | `object?` | LLM Map 阶段输出（降级模式下为 null） |
| `analysis.tags` | `string[]` | 枚举：BUGFIX/FEATURE/REFACTOR/PERF/DOCS/TEST/CHORE/HOTFIX |
| `analysis.riskLevel` | `string` | 枚举：LOW/MEDIUM/HIGH/CRITICAL |
| `analysis.messageQuality` | `string?` | 枚举：CLEAR/VAGUE/MINIMAL（仅模糊提交带此字段） |

#### 3.5.2 `volatileFiles[]` — 易挥发文件

```json
[
  {
    "path": "src/main/java/com/example/order/OrderService.java",
    "changeCount": 15,
    "windowDays": 7,
    "contributors": ["Alice", "Bob"],
    "linesChanged": 420
  }
]
```

#### 3.5.3 `crossDomainCommits[]` — 跨域提交

```json
[
  {
    "hash": "def5678",
    "shortHash": "def5",
    "author": "Bob",
    "authorEmail": "bob@example.com",
    "date": "2024-01-20T09:15:00",
    "message": "feat: integrate order with inventory and notification",
    "modules": ["order", "inventory", "notification", "user"],
    "filesChanged": 18,
    "analysis": { }
  }
]
```

结构同 `giantCommits`，`analysis` 字段规则相同。

#### 3.5.4 `vagueCommits[]` — 模糊提交

```json
[
  {
    "hash": "ghi7890",
    "shortHash": "ghi7",
    "author": "Charlie",
    "authorEmail": "charlie@example.com",
    "date": "2024-01-22T16:45:00",
    "message": "fix: 修复了订单相关的BUG",
    "vagueReason": "只说'修复BUG'，未说明具体问题、影响范围和根因",
    "score": 25,
    "analysis": {
      "intent": "意图不明：仅提到'订单相关BUG'",
      "tags": ["BUGFIX"],
      "riskLevel": "MEDIUM",
      "messageQuality": "VAGUE",
      "qualityReason": "未说明修复了哪个具体BUG及原因"
    }
  }
]
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `vagueReason` | `string` | LLM 返回的模糊原因（降级模式下为 Java 评分引擎生成的规则原因） |
| `score` | `int` | 降级模式下的特征评分（LLM 模式下为 null） |

### 3.6 `globalInsight` — 全局洞察

```json
{
  "summary": "本迭代共 1234 次提交，整体代码质量中等偏上...",
  "recommendations": [
    "建议加强提交信息规范，模糊提交占比约 18%",
    "order/OrderService.java 变更过于频繁，建议评估是否需要重构"
  ],
  "healthScore": 72
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `summary` | `string?` | LLM Reduce 阶段生成的全局总结（降级模式下为 null） |
| `recommendations` | `string[]?` | LLM 生成的改进建议（降级模式下为 null） |
| `healthScore` | `int` | 基于元数据的健康度评分（0-100，纯 Java 计算，始终有值） |

`healthScore` 计算公式（Java 端确定性计算）：

```
base = 100
base -= vagueCommits.length * 2           // 模糊提交扣分
base -= giantCommits.length * 3            // 巨型提交扣分
base -= volatileFiles.length * 5           // 易挥发文件扣分
base -= crossDomainCommits.length * 2      // 跨域提交扣分
clamp(base, 0, 100)
```

### 3.7 `activityHeatmap` — 团队活跃热力图数据

```json
{
  "data": [
    {"day": 0, "hour": 9, "count": 45},
    {"day": 0, "hour": 10, "count": 52},
    {"day": 1, "hour": 14, "count": 38}
  ],
  "maxCount": 52
}
```

| 字段 | 说明 |
|---|---|
| `day` | 星期（0=周一, 6=周日） |
| `hour` | 小时（0-23） |
| `count` | 该时段的全团队提交总数 |
| `maxCount` | 全局最大提交数（用于热力图归一化） |

### 3.8 `meta` — 元信息

```json
{
  "analyzedAt": "2024-01-25T14:30:00",
  "llmEnabled": true,
  "llmModel": "deepseek-chat",
  "analysisDurationMs": 45000,
  "version": "1.0.0"
}
```

---

## 4. Java DTO 映射

### 4.1 DTO 类层次

```
ReportDataDTO (顶层，对应完整 JSON)
├── RepositoryInfoDTO
├── List<AuthorStatsDTO>
├── List<ModuleStatsDTO>
├── AnomalyGroupDTO
│   ├── List<GiantCommitDTO>
│   ├── List<VolatileFileDTO>
│   ├── List<CrossDomainCommitDTO>
│   └── List<VagueCommitDTO>
├── GlobalInsightDTO
├── ActivityHeatmapDTO
└── AnalysisMetaDTO

LLM 分析结果嵌套在对应 DTO 内：
├── CommitAnalysisDTO (嵌入 GiantCommitDTO / CrossDomainCommitDTO / VagueCommitDTO)
└── AuthorProfileDTO (嵌入 AuthorStatsDTO)
```

### 4.2 序列化约定

- 使用 Jackson 序列化
- null 值字段保留在 JSON 中（Vue3 端通过 `v-if` 控制渲染）
- 日期格式：`yyyy-MM-dd'T'HH:mm:ss`
- 枚举值使用大写下划线（如 `BUGFIX`、`HIGH`）

---

## 5. CLI 参数接口

```bash
java -jar repordar.jar analyze <repo-url-or-path> [options]
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `<repo>` | 位置参数 | 是 | - | 远程 URL 或本地路径 |
| `--output` | string | 否 | `./reports` | 报告输出目录 |
| `--since` | string | 否 | 30 天前 | 分析起始日期 (yyyy-MM-dd) |
| `--until` | string | 否 | 今天 | 分析截止日期 (yyyy-MM-dd) |
| `--branch` | string | 否 | 默认分支 | 分析的分支 |
| `--llm-api-key` | string | 否 | 配置文件值 | LLM API Key |
| `--llm-base-url` | string | 否 | 配置文件值 | LLM API 基础 URL |
| `--llm-model` | string | 否 | 配置文件值 | LLM 模型名称 |
| `--port` | int | 否 | 8080 | SSE 进度服务端口 |
| `--no-browser` | flag | 否 | false | 不自动打开浏览器 |

退出码：`0` 成功，`1` 参数错误，`2` 运行时错误。
