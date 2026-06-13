# 🔍 RepoRadar — 团队代码脉搏雷达

**RepoRadar** 是一款 AI 驱动的 Git 仓库分析工具。输入一个仓库地址，它自动克隆、提取元数据、检测异常提交、调用 LLM 深度分析，最终生成一份交互式可视化报告——帮你一眼看穿团队的代码健康状态。

```
输入: git clone URL 或本地路径
输出: 一份包含热力图、异常诊断、AI 洞察的交互式 HTML 报告
```

---

## 它解决了什么问题

团队协作中，Git 提交历史藏着大量信号，但没人会去翻几千条 `git log`。RepoRadar 把这些信号变成可操作的分析：

| 问题                    | RepoRadar 的回答                      |
| --------------------- | ---------------------------------- |
| 谁在凌晨 3 点还在提交？         | **活跃热力图** — 按星期×小时可视化提交密度          |
| 哪些提交改了 500+ 行？        | **巨型提交检测** — 标记单次改动过大的提交           |
| 哪些文件天天被改来改去？          | **易挥发文件检测** — 发现高频变更的热点文件          |
| 谁一个提交改了 5 个模块？        | **跨域提交检测** — 识别职责不清的提交             |
| "update"、"fix" 算什么提交？ | **模糊提交检测** — AI 语义识别无意义提交信息        |
| 这一切说明了什么？             | **AI 全局洞察** — LLM 综合分析，给出健康评分和改进建议 |

### 架构亮点

- **LLM 多厂商支持** — 兼容 OpenAI 协议，理论上支持所有兼容 API 的模型；已在 DeepSeek 和智谱 GLM 上测试通过，其他厂商暂未验证
- **优雅降级** — 不配 API Key 也能跑，纯规则分析保证基线功能
- **代理自动检测** — 自动读取 `~/.gitconfig` 代理配置，和 `git clone` 体验一致
- **单 JAR 零依赖** — `java -jar` 即跑，无需安装数据库或前端构建

---

## 怎么跑

### 环境要求

- **Java 17+** (推荐 JDK 21)
- **Maven 3.8+** (构建用)
- 可选：任一 LLM API Key（DeepSeek / OpenAI / 智谱 GLM / 通义千问 / Moonshot）

### 构建

```bash
git clone https://github.com/frank/RepoRadar.git
cd RepoRadar
./mvnw package -DskipTests
```

产出：`target/repordar-1.0.0.jar`

### 基础用法

> ⚠️ **第一个参数必须是 Git 仓库地址**（远程 URL 或本地路径），不可省略。

```bash
# 分析远程仓库（纯规则模式，无需 API Key）
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git

# 分析本地仓库
java -jar target/repordar-1.0.0.jar /path/to/your/repo

# 指定时间范围
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git \
  --since 2026-01-01 --until 2026-06-01
```

### 网络代理

工具会按以下优先级自动检测代理配置，**大多数情况下无需手动指定**：

1. `--proxy` CLI 参数
2. `HTTPS_PROXY` / `HTTP_PROXY` 环境变量
3. `~/.gitconfig` 中的 `http.<url>.proxy` 配置（自动匹配目标 URL）

也就是说，如果你在 git config 中已经配了代理（如 `git config --global http.https://github.com/.proxy http://127.0.0.1:7890`），本工具会自动读取，和 `git clone` 体验一致。

如果需要手动指定：

```bash
# 方式一：CLI 参数
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git \
  --proxy http://127.0.0.1:7890

# 方式二：环境变量
export HTTPS_PROXY=http://127.0.0.1:7890
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git
```

> ⚠️ 代理设置仅对 HTTPS 协议生效。SSH 协议（`git@github.com:...`）不受 HTTP 代理影响——如果你的 SSH 连接 GitHub 需要代理，请在 `~/.ssh/config` 中配置 `ProxyCommand`，或改用 HTTPS 地址。

### 接入 LLM（推荐）

```bash
# DeepSeek（自动识别厂商 URL）
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git \
  --llm-api-key sk-your-deepseek-key \
  --llm-model deepseek-chat

# 智谱 GLM
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git \
  --llm-api-key your-zhipu-key \
  --llm-model GLM-4-Flash

# 任意 OpenAI 兼容 API
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git \
  --llm-api-key your-key \
  --llm-base-url https://your-api.com/v1 \
  --llm-model your-model
```

分析完成后会在终端输出报告路径：

```
🔍 RepoRadar 开始分析: https://github.com/your-org/your-repo.git
✅ 分析完成！报告: ./reports/repordar-your-repo-20260613-143052.html
```

### 完整参数

```
用法: java -jar repordar-1.0.0.jar <仓库地址> [选项]

必填:
  <repo>                   Git 仓库 HTTPS/SSH URL 或本地路径

选项:
  --output <目录>          报告输出目录 (默认: ./reports)
  --since <日期>           分析起始日期 (yyyy-MM-dd)
  --until <日期>           分析截止日期 (yyyy-MM-dd)
  --branch <分支>          分析的分支 (默认: 仓库默认分支)
  --llm-api-key <key>      LLM API Key
  --llm-base-url <url>     LLM API 基础 URL (自动推断可省略)
  --llm-model <name>       LLM 模型名称 (如 deepseek-chat, GLM-4-Flash)
  --proxy <url>            HTTP 代理 (如 http://127.0.0.1:7890)
```

### 环境变量（替代 CLI 参数）

| 变量 | 用途 |
|------|------|
| `REPORADAR_LLM_API_KEY` | LLM API Key |
| `REPORADAR_LLM_MODEL` | LLM 模型名称 |
| `HTTPS_PROXY` / `HTTP_PROXY` | 网络代理（克隆仓库 + LLM 调用） |

```bash
export REPORADAR_LLM_API_KEY=sk-your-key
export REPORADAR_LLM_MODEL=deepseek-chat
export HTTPS_PROXY=http://127.0.0.1:7890
java -jar target/repordar-1.0.0.jar https://github.com/your-org/your-repo.git
```

---

## 分析流程

```
 CLONE ──► METADATA ──► ANOMALY ──► LLM_VAGUE ──► LLM_MAP ──► LLM_REDUCE ──► REPORT
   │           │           │            │              │             │           │
   ▼           ▼           ▼            ▼              ▼             ▼           ▼
 克隆仓库   提取元数据   异常检测    模糊提交扫描   逐条语义分析   全局洞察    生成报告
                        ┌─────────────────────────────────────────────┐
                        │ 巨型提交 · 易挥发文件 · 跨域提交 · 模糊提交  │
                        └─────────────────────────────────────────────┘
```

| 阶段             | 说明                            | LLM 不用时  |
| -------------- | ----------------------------- | -------- |
| **CLONE**      | JGit 克隆/打开仓库，分析完自动清理          | 同        |
| **METADATA**   | 提取每次提交的作者、时间、diff、模块          | 同        |
| **ANOMALY**    | 规则引擎检测巨型/易挥发/跨域提交             | 同（纯规则）   |
| **LLM_VAGUE**  | 批量扫描模糊提交（先规则预过滤，再 LLM 判断）     | 规则评分引擎替代 |
| **LLM_MAP**    | 对异常提交逐条分析：意图、风险、消息质量          | 跳过       |
| **LLM_REDUCE** | 综合全局统计，生成健康评分和改进建议            | 跳过       |
| **REPORT**     | 组装数据 → JSON 注入 HTML 模板 → 输出报告 | 同        |

---

## 报告预览

生成的 HTML 报告包含：

- **总览卡片** — 提交数、贡献者、健康评分
- **作者统计** — 每人提交数、活跃时段画像（早鸟/稳定/夜猫）
- **模块热力图** — 哪些模块变更最频繁
- **活跃热力图** — 星期×小时的提交密度可视化
- **异常诊断** — 四类异常的详细列表和检测标准
- **AI 洞察** — LLM 生成的全局分析、风险提示、改进建议

> 报告使用 Vue3 + ECharts 渲染，纯 CDN 加载，无需构建。通过 `__INJECT_DATA__` 模板注入，LLM 输出不直接注入 HTML。

---

## 技术栈

| 层    | 技术                                                               |
| ---- | ---------------------------------------------------------------- |
| 后端   | Java 17, Spring Boot 3.2, JGit 6.8, Picocli 4.7, Jackson, Lombok |
| 前端   | Vue3 + ECharts 5（CDN，无构建）                                        |
| LLM  | OpenAI 兼容 API（DeepSeek / OpenAI / GLM / Qwen / Moonshot）         |
| 构建   | Maven，单 JAR 部署                                                   |
| 静态分析 | P3C (阿里巴巴规约) + SpotBugs + find-sec-bugs (pre-commit hook)        |

---

## 开发

```bash
# 运行测试
./mvnw test

# 构建打包
./mvnw package -DskipTests

# 单测试类
./mvnw test -Dtest=GiantCommitRuleTest

# 跳过 pre-commit hook（仅紧急情况）
git commit --no-verify
```

---

## License

MIT
