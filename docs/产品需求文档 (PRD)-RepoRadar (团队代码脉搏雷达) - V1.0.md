# 产品需求文档 (PRD): RepoRadar (团队代码脉搏雷达) - V1.0

## 1. 产品愿景与定位

**一句话描述**：一个面向研发经理/架构师的本地 AI Git 分析工具，能读懂提交记录和代码差异，用自然语言告诉你团队里真正发生了什么。
**核心差异化**：传统工具只做冰冷统计，RepoRadar 利用 LLM 进行**语义级分析**，揭示提交动机和架构异味。
**架构设计原则**：

1. **小工具，不造 SaaS**：无数据库，无异步队列，纯流式管线处理。
2. **AI 作为翻译组件**：Java 负责确定性计算与过滤，LLM 负责语义提取，绝不硬塞全量 Diff。
3. **确定性防线**：LLM 输出必须被 Java 反序列化为强类型 DTO；严禁 LLM 生成 UI 标签；视图层通过模板注入保证绝对稳定。
4. **优雅降级**：系统在缺失 LLM 配置或 LLM 不可用时，自动降级为纯元数据统计模式，保证基线功能可用。

---

## 2. 核心用户流程

### 2.1 触发与执行 (CLI 交互)

用户在终端执行命令（支持覆盖配置）：

```bash
# 常规执行（读取本地配置或环境变量）
java -jar repordar.jar analyze https://github.com/xxx/yyy.git --output ./reports --since "2023-10-01"
# 临时覆盖 LLM 配置
java -jar repordar.jar analyze ... --llm-api-key sk-xxx --llm-base-url https://api.deepseek.com/v1
```

* 系统启动内嵌轻量级 HTTP Server，自动打开浏览器 `http://localhost:8080` 展示实时 SSE 进度日志。
* 分析完成后，终端提示完成，浏览器高亮显示生成的报告文件路径。

---

## 3. 功能需求 (MVP - 7天范围)

### 3.1 配置与降级管理层 (新增)

系统启动时按优先级读取 LLM 配置：`CLI 参数` > `环境变量 (REPORADAR_LLM_*)` > `本地配置文件 (application.yml)`。

* **配置项**：`api-key`, `base-url` (兼容代理/本地 Ollama), `model-name`。
* **无 Key 降级模式**：若未检测到有效 API Key，系统在控制台输出警告，自动跳过所有 LLM 调用阶段。报告依然生成，但 AI 洞察区域显示“未配置 LLM，跳过语义分析”。
  
  ### 3.2 数据接入与准备层
* **Git 数据拉取**：支持输入远程 URL（自动 `git clone --bare` 到临时目录）或本地仓库路径。
* **资源清理**：分析完毕或异常中断时，自动清理临时克隆的裸仓库。
  
  ### 3.3 核心分析引擎 (卫星图 + 无人机航拍)
  
  全由 Java/JGit 毫秒级完成，提取**元数据**：
* **基础统计**：按作者统计提交次数、增删行数、活跃时间段分布。
* **模块归属**：根据包路径将文件变动映射到业务模块。
* **异常嗅探 (启发式规则)**：巨型提交 (>500行)、易挥发文件 (短期修改>10次)、跨域提交 (>3模块)、模糊提交 (匹配黑名单)。
  
  ### 3.4 LLM 语义翻译层 (Map-Reduce 模式)
  
  仅对**异常嗅探**命中的提交触发。**核心约束：LLM 必须输出结构化 JSON，由 Java 反序列化为 DTO。**
* **Map 阶段 (单次提交翻译)**：
  * 输入：Commit message + 截断的 Diff 摘要。
  * Prompt 强制要求：`请分析并以 JSON 输出：{"intent": "业务意图", "tags": ["BUGFIX"], "risk_level": "HIGH"}`。
  * 容错：若 LLM 返回非法 JSON，Java 捕获异常，该条降级为纯文本记录。
* **Reduce 阶段 (全局洞察提取)**：
  * **作者画像/模块洞察**：输入统计摘要与 Map 结果，LLM 输出自然语言评估文本（String 类型即可，由 Java 封装入 `InsightDTO`）。
    
    ### 3.5 静态报告渲染层 (单向数据流)
    
    **严禁 LLM 生成 HTML 代码。** 采用“Java 聚合 JSON -> 模板占位符注入 -> 客户端渲染”管线。
* **技术栈**：Vue3 (CDN) + ECharts (CDN) + TailwindCSS (CDN)。
* **数据注入**：Java 将所有分析结果（含 LLM 生成的 DTO）序列化为一个大 JSON 字符串，替换 HTML 模板中的 `__INJECT_DATA__` 占位符。
* **客户端渲染**：Vue3 在浏览器端读取注入的 JSON，驱动 ECharts 图表和文本卡片渲染。
* **报告布局**：
  * **Header**：仓库基础信息概览。
  * **左栏 - 团队脉搏**：ECharts 贡献图 + 作者画像卡片 (LLM 文本)。
  * **右栏 - 架构预警**：异味清单 + LLM 风险解读。
  * **底栏 - 迭代总结**：全局洞察文本。

---

## 4. 技术架构设计 (极简正交架构)

坚决摒弃复杂状态管理，采用**流式管线架构**：

1. **CLI 触发器** (Picocli) -> 解析参数，校验 LLM 配置。
2. **SSE 进度网关** (Spring Web `SseEmitter`) -> 推送管线节点日志。
3. **分析管线 (同步顺序执行)**：
   * `CloneTask` (JGit) -> `MetadataExtractor` (JGit + Stream) -> `AnomalyFilter` (规则引擎) -> `LLMTranslator` (Map-Reduce, 强制 JSON 输出/降级控制) -> `ReportGenerator` (序列化 DTO + 替换模板占位符)。
4. **终止**：管线执行完毕，输出 HTML，Spring Boot 进程正常退出。

---

## 5. CC (Claude Code) 工作流设计

### 5.1 核心目录结构

```text
.claude/
  CLAUDE.md            # 全局架构约束：禁止 JGit 层混入 LLM 调用；LLM 必须输出 JSON；严禁生成 HTML 视图代码
  skills/
    add-metric.md      # 规范如何添加新的元数据提取指标
    explain-anomaly.md # 规范如何编写异常规则及对应的结构化 Prompt
  agents/
    jgit-expert.md     # JGit 复杂 API 专家
```

### 5.2 强制 TDD 策略

在 `CLAUDE.md` 中规定：所有 `MetadataExtractor`、`AnomalyFilter` 以及 **`LLMTranslator` 的 JSON 反序列化逻辑**，必须严格遵循 Red-Green-Refactor 流程。
---

## 6. 7天冲刺排期

| 日期        | 核心目标       | 产出                                            |
|:--------- |:---------- |:--------------------------------------------- |
| **Day 1** | 基建与配置防线    | CLI 骨架、LLM 配置读取与降级逻辑、SSE 通路、`.claude/` 基础配置   |
| **Day 2** | 元数据引擎与 TDD | 异常嗅探规则实现、模块归属逻辑 (严格 TDD)                      |
| **Day 3** | LLM 语义层接入  | Map 阶段 JSON 输出强制与反序列化容错、Reduce 阶段洞察提取         |
| **Day 4** | 报告引擎与模板注入  | Vue3+ECharts 模板编写、`__INJECT_DATA__` 注入逻辑、联调渲染 |
| **Day 5** | CC 工作流打磨   | 编写核心 Skill，用 CC 跑通一次扩展流程 (如新增指标)              |
| **Day 6** | 全链路压测与极限场景 | 拿大型开源项目测试，处理 OOM/Token 超限/LLM 非法 JSON 返回      |
| **Day 7** | 交付与复盘      | 润色 README、录制极客风 Demo、完成老手视角的 JOURNAL.md       |

---

## 7. 验收标准

1. **降级可用性**：不配置任何 LLM API Key 运行工具，系统不崩溃，能正常输出纯统计版 HTML 报告。
2. **渲染稳定性**：双击生成的 HTML，图表渲染正常，无 JS 报错，页面不因 LLM 输出内容而变形。
3. **性能达标**：分析一个 1000 次提交的中型仓库，耗时 1 分钟以内。
4. **AI 赋能证明**：通过 CC 自定义 Skill，快速扩展一个新的分析维度。
