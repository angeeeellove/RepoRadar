# RepoRadar PRD 需求澄清与设计确认

> 基于产品需求文档 (PRD) V1.0 的需求分析、模糊点消除与设计确认。
> 日期：2026-06-12

---

## 1. 异常嗅探规则（Java 规则引擎）

仅保留**可通过纯数值/路径判断**的规则，语义判断交由 LLM 或 Java 特征评分处理。

| 规则 | 判定逻辑 | 配置项 |
|---|---|---|
| 巨型提交 | added + deleted 总行数 > 500 | `anomaly.giant-commit-threshold` (默认 500) |
| 易挥发文件 | 可配置时间窗口内修改 >10 次 | `anomaly.volatile-window-days` (默认 7)，`anomaly.volatile-threshold` (默认 10) |
| 跨域提交 | 单次提交涉及 >3 个一级目录模块 | `anomaly.cross-domain-threshold` (默认 3) |

**模块划分规则**：自动按一级目录划分，零配置。

- 示例：`src/main/java/com/example/order/...` → 模块 `order`
- 取 `src/` 以下的第一级目录名作为模块标识
- 非代码文件同理，按顶层目录归属

---

## 2. 模糊提交检测（双模式）

**原 PRD 方案（Java 黑名单匹配）已废弃**，原因：黑名单无法捕捉语义模糊（如 "fix: 修复了订单相关的BUG" 长度和格式均正常，但内容模糊）。

### 2.1 LLM 模式（主，LLM 已配置时生效）

将全量 commit message 批量发送给 LLM，一次调用返回模糊提交列表。

- ≤ 500 条提交 → 单次 LLM 调用
- \> 500 条 → 按 500 条一批分批调用，合并结果
- LLM Prompt 要求返回结构化 JSON：
  ```json
  [
    {"commit": "abc1234", "reason": "只说'修复BUG'，未说明具体问题和原因"},
    {"commit": "def5678", "reason": "仅写了'优化代码'，未说明优化了什么"}
  ]
  ```
- 不模糊的提交不在返回结果中

### 2.2 降级模式（LLM 未配置时生效）

纯 Java 特征评分，零外部依赖：

```
满分 100，低于 50 判定模糊
```

**减分项：**
| 条件 | 扣分 |
|---|---|
| 包含泛指词（"相关BUG"、"一些修改"、"若干调整"、"部分优化"） | -30 |
| 缺少具体名词对象（只有动词如"优化"、"调整"、"修复"没有说改了什么） | -25 |
| Conventional Commits 格式中 description 部分长度 ≤ 5 字符 | -20 |
| 匹配已知模糊模式（"优化了代码"、"修复问题"、"小改动"） | -25 |

**加分项：**
| 条件 | 加分 |
|---|---|
| 引用了仓库中的具体模块/组件名（从仓库路径自动提取） | +15 |
| 包含 Issue 编号（如 #123、JIRA-456） | +10 |
| 包含错误码或异常类名（如 NullPointerException、OOM、ERR-001） | +10 |

### 2.3 Conventional Commits 兼容

两种模式均先解析 Conventional Commits 格式：

- 正则：`^(\w+)(\([\w-]+\))?:\s*(.+)$`
- 只分析 description 部分，不将 type 前缀（fix/feat/refactor 等）纳入模糊判断
- 不匹配该格式的 commit message，分析整条内容

---

## 3. LLM 语义层

### 3.1 Map 阶段（单次提交深度分析）

**触发条件**：仅对异常嗅探命中的提交触发（巨型/易挥发/跨域/模糊）。

**输入构造**：
- Commit message + 截断的 Diff 摘要
- Diff 截断策略：上限 ~4000 tokens
- 截断优先级：变更文件名列表 → `@@ hunk header` → 实际 diff 行
- 超限部分丢弃，不拆分多次调用

**输出格式**（强制 JSON → Java 反序列化为 DTO）：
```json
{
  "intent": "修复订单创建时并发支付竞态条件",
  "tags": ["BUGFIX"],
  "risk_level": "HIGH",
  "message_quality": "CLEAR",
  "quality_reason": "明确说明了问题和影响范围"
}
```

**Tags 枚举**（多选，写死在代码中）：

`BUGFIX` | `FEATURE` | `REFACTOR` | `PERF` | `DOCS` | `TEST` | `CHORE` | `HOTFIX`

**Risk Level 枚举**（单选，写死在代码中）：

`LOW` | `MEDIUM` | `HIGH` | `CRITICAL`

**容错**：LLM 返回不在枚举中的值 → 降级为 `CHORE` / `LOW`。LLM 返回非法 JSON → 该条降级为纯文本记录。

### 3.2 Reduce 阶段（全局洞察提取）

**输入**：统计摘要 + Map 阶段全部结果

**输出**（自然语言文本，封装入 `InsightDTO`）：
- 作者画像：每位核心贡献者的技术特征、贡献模式评估
- 模块洞察：各模块的变更频率、风险趋势
- 全局总结：迭代整体质量、架构健康度、改进建议

---

## 4. 报告渲染层

### 4.1 技术栈

Vue3 (CDN) + ECharts (CDN) + TailwindCSS (CDN)，无构建步骤。

### 4.2 数据注入

Java 将所有分析结果（含 LLM 生成的 DTO）序列化为一个大 JSON 字符串，替换 HTML 模板中的 `__INJECT_DATA__` 占位符。Vue3 在浏览器端读取注入的 JSON 驱动渲染。

**严禁 LLM 生成 HTML 代码。**

### 4.3 报告布局

桌面端固定宽度（1200px+），MVP 阶段不做响应式适配。

- **Header**：仓库基础信息概览
- **左栏 — 团队脉搏**：ECharts 贡献热力图 + 作者画像卡片（LLM 文本）
- **右栏 — 架构预警**：异常清单 + LLM 风险解读
- **底栏 — 迭代总结**：全局洞察文本

### 4.4 活跃时间段展示

- 底层按小时粒度（0-23）统计每作者提交分布
- ECharts 热力图展示：横轴 = 星期，纵轴 = 时段
- 作者统计卡片中标注核心活跃时段（如 "14:00-18:00"）

---

## 5. SSE 进度事件

结构化 JSON 事件，阶段枚举驱动。

**阶段枚举**：`CLONE → METADATA → ANOMALY → LLM_VAGUE → LLM_MAP → LLM_REDUCE → REPORT`

**事件格式**：
```
event: progress
data: {"stage": "CLONE", "message": "正在克隆仓库...", "percent": 10}

event: progress
data: {"stage": "METADATA", "message": "提取元数据 523/1000...", "percent": 35}

event: progress
data: {"stage": "LLM_VAGUE", "message": "LLM 语义扫描 1/2 批次...", "percent": 55}
```

**降级行为**：LLM 未配置时，`LLM_VAGUE`、`LLM_MAP`、`LLM_REDUCE` 阶段跳过，进度直接跳到 `REPORT`。

---

## 6. 配置与三级降级

### 6.1 配置优先级

`CLI 参数` > `环境变量 (REPORADAR_LLM_*)` > `本地配置文件 (application.yml)`

### 6.2 降级矩阵

| 场景 | 元数据统计 | 模糊提交检测 | LLM 深度分析 | 报告 |
|---|---|---|---|---|
| 完整配置（有 API Key） | ✅ | ✅ LLM 批量扫描 | ✅ Map-Reduce | 完整报告 |
| 无 LLM Key | ✅ | ✅ Java 特征评分 | ❌ 跳过 | 报告无 AI 洞察区域 |
| 无 LLM + 极简 | ✅ | ⚠️ 基础规则 | ❌ 跳过 | 纯统计报告 |

### 6.3 降级提示

无 LLM Key 时：
- 控制台输出警告
- 报告 AI 洞察区域显示 "未配置 LLM，跳过语义分析"
- 系统不崩溃，基线功能可用

---

## 7. 性能指标

| 指标 | 目标 |
|---|---|
| 纯元数据分析（Clone + Metadata + Anomaly Filter） | 1000 提交 ≤ 60 秒 |
| LLM 调用 | 仅要求线性增长，不设硬性上限 |
| 内存占用 | 默认 JVM 堆即可，不要求特殊配置 |

---

## 8. 部署要求

- **形式**：单个可执行 JAR
- **运行**：`java -jar repordar.jar analyze <repo-url> --output ./reports --since "2023-10-01"`
- **依赖**：仅 JRE 8+，零额外安装
- **无本地 ML 模型文件**，模糊提交检测降级模式为纯 Java 实现
- **临时文件**：`git clone --bare` 到系统临时目录，分析完毕或异常中断时自动清理

---

## 9. 分析管线最终架构

```
CLI 参数解析 (Picocli)
  → LLM 配置校验与降级判定
  → SSE 进度网关 (SseEmitter)
  → 分析管线（同步顺序执行）：
      1. CloneTask (JGit)
      2. MetadataExtractor (JGit + Stream)
      3. AnomalyFilter (规则引擎：巨型/易挥发/跨域)
      4. VagueCommitDetector
         ├─ LLM 模式：批量 commit message → LLM → 模糊提交列表
         └─ 降级模式：Java 特征评分
      5. LLMTranslator (Map-Reduce，仅对异常提交)
         ├─ Map：逐条深度分析（意图/标签/风险/消息质量）
         └─ Reduce：全局洞察（作者画像/模块洞察/总结）
      6. ReportGenerator (序列化 DTO + 替换模板占位符)
  → 输出 HTML，Spring Boot 进程退出
```
