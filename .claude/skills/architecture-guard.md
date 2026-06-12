# 架构边界守护

> 本 Skill 是架构护栏，防止分层被破坏。每次涉及跨层调用时必须参照。

## 1. 分层依赖原则（严禁越界）

```
CLI → Pipeline → [GitCloner → MetadataExtractor → AnomalyFilter → LlmMap/Reduce → ReportGenerator]
```

每一层只能调用下一层，严禁反向依赖或跨层调用。

| 层 | 包 | 职责 | 禁止 |
|---|---|------|------|
| JGit 提取层 | `git/` | 从 Repository 提取原始数据 | 禁止 HTTP 调用、LLM 调用、业务判断 |
| 规则过滤层 | `anomaly/` | 应用启发式规则，输出标记结果 | 禁止 LLM 调用、HTML 生成 |
| LLM 翻译层 | `llm/` | 与 LLM API 交互，输出结构化 DTO | 禁止直接操作 JGit、生成 HTML |
| 渲染层 | `report/` | 将聚合 DTO 注入 HTML 模板 | 禁止业务逻辑、LLM 调用 |
| 管线编排层 | `pipeline/` | 串联上述各层 | 只做编排，不含业务逻辑 |

## 2. 技术栈锁定（严禁漂移）

| 项 | 锁定 | 禁止 |
|---|---|---|
| 后端框架 | Spring Boot 3.2 + Java 17 | 禁止引入 Micronaut/Quarkus |
| Git 操作 | JGit 6.8 原生 API | **严禁 `Runtime.exec("git ...")`** |
| JSON 处理 | Jackson（Spring Boot 内置） | 禁止 Gson/fastjson |
| 前端 | CDN 引入 Vue3 + ECharts 5 + TailwindCSS 3 | **严禁 Webpack/Vite/Node.js/`.vue` 文件** |
| 前端渲染 | 单个 HTML 文件 + `<script>` 标签 | 禁止 JSX、禁止构建步骤 |

## 3. 数据流约束

- DTO 不含业务逻辑，只承载数据
- LLM 输出**不可信**，必须经过 Jackson 反序列化校验 + 枚举值校验
- 视图层通过 `__INJECT_DATA__` 注入 JSON，由 Vue3 渲染，**严禁 LLM 生成 HTML**
