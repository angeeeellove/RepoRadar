# RepoRadar 项目约束

## 架构红线

- **JGit 层 (`git/`) 严禁调用 LLM** — Git 操作与语义分析完全隔离
- **LLM 层 (`llm/`) 必须输出结构化 JSON** — Java 端强制反序列化为 DTO，严禁 LLM 输出直接用于 UI
- **严禁 LLM 生成 HTML 视图代码** — 视图层通过 `__INJECT_DATA__` 模板注入，保证渲染稳定
- **优雅降级** — LLM 不可用时，纯 Java 逻辑必须保证基线功能可用，系统不崩溃

## 技术栈

- Java 17, Spring Boot 3.2, Maven
- JGit 6.8, Picocli 4.7, Jackson
- Vue3 + ECharts 5 + TailwindCSS 3 (CDN, 无构建)
- 单 JAR 部署，零额外依赖

## 构建与测试

- 运行测试: `./mvnw test`
- 构建打包: `./mvnw package -DskipTests`
- 单测试类: `./mvnw test -Dtest=ClassName`

---

## Git 提交规范（必须遵守）

**所有 commit message 必须使用中文，遵循 Conventional Commits 规范。**

### 格式

```
<emoji> <type>(<scope>): <中文描述>
```

### 类型与 Emoji

| Type | Emoji | 使用场景 |
|---|---|---|
| `feat` | ✨ | 新功能 |
| `fix` | 🐛 | Bug 修复 |
| `docs` | 📝 | 文档变更 |
| `style` | 🎨 | 代码格式（不影响逻辑） |
| `refactor` | ♻️ | 重构（非新功能非修复） |
| `perf` | ⚡ | 性能优化 |
| `test` | ✅ | 测试相关 |
| `chore` | 🔧 | 构建/工具/配置变更 |

### Scope 范围

`git` | `anomaly` | `llm` | `report` | `sse` | `dto` | `cli` | `config` | `pipeline` | `全栈`

### 规则

1. **主题行不超过 50 个字符**，中文描述用祈使语气（"添加" 而非 "添加了"）
2. **主题行末尾不加句号**
3. **每个 commit 只做一件事** — 功能代码、测试代码、规范修复分开提交
4. **禁止无意义的提交** — 不允许 "update"、"fix"、"wip" 等模糊描述
5. **功能开发先测试后实现** — TDD 流程中测试提交和实现提交分开
6. **规范修复单独提交** — `/review-standards` 的修复不与功能代码混在一起
7. **提交前必须通过代码扫描** — git pre-commit hook 自动运行 P3C + SpotBugs + find-sec-bugs，扫描不过禁止提交；跳过用 `--no-verify`（仅紧急情况）

### 示例

```
✨ feat(git): 添加 Git 仓库克隆与自动清理
🐛 fix(anomaly): 修复易挥发文件滑动窗口计算边界错误
📝 docs: 更新接口设计文档 SSE 事件格式
♻️ refactor(llm): 提取 JSON 解析逻辑为公共方法
⚡ perf(metadata): 使用 Stream API 替代循环统计
✅ test(anomaly): 添加模糊提交评分引擎单元测试
🔧 chore: 初始化 Maven 项目结构与 Spring Boot 入口
```

---

## 编码规范（必须遵守）

### 一、命名规约（阿里巴巴）

1. **类名**：UpperCamelCase，名词。`GitCloner`、`MetadataExtractor`、`CommitAnalysisDto`
2. **方法名**：lowerCamelCase，动词或动词短语。`extractMetadata()`、`detectModule()`、`computeScore()`
3. **常量**：全大写下划线分隔。`MAX_DIFF_TOKENS`、`DEFAULT_THRESHOLD`
4. **包名**：全小写，单词间无分隔符。`com.repordar.anomaly`
5. **DTO/Record 字段**：lowerCamelCase。与 JSON 字段名通过 `@JsonProperty` 映射 snake_case
6. **布尔变量/方法**：不以 is 开头（部分序列化框架会冲突）。用 `hasApiKey()` 而非 `isApiKeyPresent()`
7. **抽象类**：以 `Base` 或 `Abstract` 开头
8. **异常类**：以 `Exception` 结尾。`LlmApiException`、`GitCloneException`
9. **测试类**：以 `Test` 结尾，与被测类同名。`GiantCommitRuleTest`
10. **禁止**：拼音命名、中英混合、下划线或美元符号开头

### 二、异常处理规约

1. **不要捕获顶层 Exception** — 捕获具体异常类型，如 `IOException`、`JsonProcessingException`
2. **不要吞掉异常** — catch 块中至少记录日志，禁止空 catch 块
3. **不要用异常做流程控制** — 异常只用于真正的异常场景
4. **方法返回值可以为 null** — 必须在 Javadoc 中标注 `@return` 是否可能为 null
5. **LLM 调用失败** — 捕获异常后降级（返回 null 或空结果），不向上抛出，不中断管线
6. **资源关闭** — 使用 try-with-resources，确保 `Repository`、`Git`、`DiffFormatter` 等正确关闭
7. **对外错误信息** — SSE 错误事件中不暴露堆栈信息，使用用户友好的中文描述

### 三、日志规约

1. 使用 SLF4J（Spring Boot 默认）
2. 日志级别：ERROR（系统错误）> WARN（降级警告）> INFO（关键节点）> DEBUG（调试细节）
3. 关键操作必须记录日志：LLM 调用、Git 克隆、报告生成
4. LLM 降级时输出 WARN 级别日志，包含失败原因
5. 禁止在循环中打印日志（避免日志爆炸）
6. 禁止在日志中输出敏感信息（API Key、Token）

### 四、集合与并发规约

1. **集合初始化指定容量** — `new ArrayList<>(expectedSize)`，避免频繁扩容
2. **Map 操作** — 使用 `computeIfAbsent`、`merge` 等方法，减少 `containsKey` + `get` 双次查找
3. **返回空集合而非 null** — 使用 `Collections.emptyList()` 或 `List.of()`
4. **subList 返回的是视图** — 注意原列表修改的影响
5. **本工具是单线程同步管线** — 不使用多线程，不使用 synchronized、volatile、Atomic 变量
6. **SimpleDateFormat 线程不安全** — 使用 `DateTimeFormatter`（本项目中使用 Java 8 时间 API）

### 五、性能规约

1. **避免在循环中创建大量对象** — 特别是 MetadataExtractor 遍历提交时
2. **字符串拼接** — 循环内使用 `StringBuilder`，非循环用 `+` 即可
3. **大集合操作使用 Stream** — 提交列表过滤、分组、统计使用 Java Stream API
4. **Diff 截断** — 严格遵守 4000 tokens 上限，避免 OOM
5. **文件操作** — 使用 `Files.readString()` / `Files.writeString()`，非场景不用逐行读写
6. **Jackson ObjectMapper** — 全局共享一个实例（线程安全），不要每次创建
7. **Git 克隆** — 使用 `--bare` 减少磁盘占用，分析完毕立即清理
8. **提前过滤** — 在 JGit 遍历阶段就按日期过滤提交，不要全量加载后再过滤

### 六、安全规约

1. **API Key 不硬编码** — 通过配置文件或环境变量读取，禁止出现在源码中
2. **API Key 不出现在日志中** — 日志输出时脱敏
3. **LLM 输出不可信** — 所有 LLM 返回的 JSON 必须经过反序列化校验和枚举值校验
4. **LLM 输出不得直接注入 HTML** — 通过 Jackson 序列化为 JSON 字符串，由 Vue3 渲染
5. **命令注入防护** — 仓库 URL 传入 JGit 前做基本校验，不拼接到 shell 命令中
6. **临时文件清理** — `git clone --bare` 的临时目录在 finally 块中确保清理
7. **输入校验** — CLI 参数（日期格式、端口号）在入口处校验

### 七、代码复杂度规约

1. **单个方法不超过 80 行** — 超过则拆分为私有方法
2. **单个类不超过 500 行** — 超过则考虑职责拆分
3. **方法参数不超过 5 个** — 超过则封装为参数对象（class）
4. **嵌套层数不超过 3 层** — if/for 嵌套过深时用卫语句或提取方法
5. **圈复杂度不超过 10** — 单个方法的分支路径（if/else/switch/case）总数不超过 10
6. **DTO 使用 `@Data` + Lombok** — 不使用 record，使用 `@Data` + `@NoArgsConstructor` + `@AllArgsConstructor`；配置类使用 `@Getter` + `@Setter`
7. **依赖注入使用构造器注入** — 不使用 `@Autowired` 字段注入

### 八、测试规约

1. **测试覆盖核心逻辑** — `AnomalyFilter`、`VagueScoringEngine`、`ModuleDetector`、`LlmMapTranslator` 的 JSON 反序列化
2. **测试命名** — `shouldXxxWhenYyy` 或 `shouldXxx` 风格，描述期望行为
3. **每个测试只验证一个行为** — 不要在一个测试方法中验证多个不相关的行为
4. **测试数据使用 @TempDir** — 不硬编码临时目录路径
5. **LLM 相关测试 mock 外部调用** — 不依赖真实 LLM API

### 九、OOP 规约

1. **单一职责** — 每个 Service/Component 只做一件事
2. **面向接口编程** — 管线各阶段（`CloneTask`、`MetadataExtractor`、`AnomalyFilter`）定义清晰接口
3. **DTO 不含业务逻辑** — DTO 只承载数据，不放业务方法
4. **工具类私有构造** — 无状态的辅助工具类使用私有构造器 + 静态方法
5. **禁止魔法值** — 阈值、超时时间等数字常量必须提取为命名常量或配置项
