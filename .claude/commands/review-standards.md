---
name: review-standards
description: 审查代码是否符合项目编码规范（阿里巴巴开发规约 + 性能 + 安全 + 复杂度），发现问题并自动修复
---

# 编码规范审查与修复

对当前 git diff 中的代码变更进行逐项规范扫描。发现问题后直接修复，修复后重新扫描，直到全部通过。

## 审查流程

1. 获取当前 git diff（未提交的变更）
2. 按以下 9 大类逐项扫描
3. 对每个发现的问题：报告 → 修复 → 验证
4. 修复完成后重新扫描，直到零问题
5. 输出最终审查报告

## 审查清单

### 一、命名规约（阿里巴巴）

- [ ] 类名是否 UpperCamelCase 名词？`GitCloner` ✓ / `gitCloner` ✗
- [ ] 方法名是否 lowerCamelCase 动词短语？`extractMetadata()` ✓ / `ExtractMetadata()` ✗
- [ ] 常量是否全大写下划线？`MAX_DIFF_TOKENS` ✓ / `MaxDiffTokens` ✗
- [ ] 包名是否全小写无分隔符？`com.repordar.anomaly` ✓ / `com.repordar.anomaly_rule` ✗
- [ ] DTO record 字段是否 lowerCamelCase？snake_case JSON 字段是否用 `@JsonProperty` 映射？
- [ ] 布尔方法是否避免了 is 前缀？`hasApiKey()` ✓ / `isApiKeyPresent()` ✗
- [ ] 异常类是否以 `Exception` 结尾？
- [ ] 测试类是否以 `Test` 结尾且与被测类同名？
- [ ] 是否存在拼音命名、中英混合、下划线/美元符号开头？

### 二、异常处理

- [ ] 是否存在 `catch (Exception e)` 顶层异常捕获？→ 改为具体异常类型
- [ ] 是否存在空 catch 块或吞掉异常？→ 至少记录日志
- [ ] 是否用异常做流程控制？→ 改为条件判断
- [ ] LLM 调用失败是否捕获并降级（返回 null/空结果），未向上抛出？
- [ ] 资源（Repository、Git、DiffFormatter）是否用 try-with-resources 关闭？
- [ ] SSE 错误信息是否对用户友好，未暴露堆栈？

### 三、日志规约

- [ ] 是否使用 SLF4J 而非 `System.out.println`？（CLI 入口提示除外）
- [ ] 日志级别是否正确？ERROR=系统错误、WARN=降级、INFO=关键节点、DEBUG=细节
- [ ] 是否在循环中打印日志？→ 提取到循环外或限制频率
- [ ] 日志中是否包含 API Key、Token 等敏感信息？→ 脱敏处理
- [ ] LLM 降级是否有 WARN 级别日志说明原因？

### 四、集合与并发

- [ ] ArrayList 初始化是否指定了预期容量？`new ArrayList<>(commits.size())`
- [ ] Map 操作是否使用了 `computeIfAbsent`/`merge` 而非 `containsKey` + `get`？
- [ ] 方法返回集合是否为空集合（`Collections.emptyList()`）而非 null？
- [ ] 是否错误使用了 SimpleDateFormat？→ 应使用 DateTimeFormatter
- [ ] 是否存在不必要的多线程代码？（本项目是单线程同步管线）

### 五、性能

- [ ] 循环中是否创建大量临时对象？→ 提取到循环外
- [ ] 循环中字符串拼接是否用了 `+`？→ 改用 `StringBuilder`
- [ ] 大集合操作是否使用了 Stream API？
- [ ] Diff 截断是否严格遵守 4000 tokens 上限？
- [ ] ObjectMapper 是否为全局共享实例？→ 不要每次创建
- [ ] Git 克隆是否使用了 bare 模式？
- [ ] 是否在早期阶段就过滤了数据（而非全量加载后过滤）？

### 六、安全

- [ ] API Key 是否硬编码在源码中？→ 移到配置文件或环境变量
- [ ] 日志中是否泄露了 API Key？→ 脱敏
- [ ] LLM 返回的 JSON 是否经过反序列化校验和枚举值校验？
- [ ] LLM 输出是否有可能直接注入 HTML？→ 必须通过 Jackson 序列化
- [ ] 仓库 URL 是否做了基本校验（防止命令注入）？
- [ ] 临时文件清理是否在 finally 块中？
- [ ] CLI 参数（日期、端口）是否在入口处做了格式校验？

### 七、代码复杂度

- [ ] 单个方法是否超过 80 行？→ 拆分为私有方法
- [ ] 单个类是否超过 500 行？→ 考虑职责拆分
- [ ] 方法参数是否超过 5 个？→ 封装为参数对象（record）
- [ ] 嵌套层数是否超过 3 层？→ 使用卫语句或提取方法
- [ ] 单个方法分支路径（if/else/switch）是否超过 10 个？→ 简化逻辑
- [ ] 不可变数据对象是否使用了 Java record？
- [ ] 是否使用了 `@Autowired` 字段注入？→ 改为构造器注入

### 八、OOP 规约

- [ ] 每个 Service/Component 是否只做一件事？
- [ ] DTO/Record 是否只承载数据，不含业务逻辑？
- [ ] 是否存在魔法值（硬编码数字）？→ 提取为命名常量或配置项
- [ ] 无状态工具类是否使用了私有构造器 + 静态方法？

### 九、测试

- [ ] 核心逻辑（AnomalyFilter、VagueScoringEngine、ModuleDetector、LlmMapTranslator）是否有测试？
- [ ] 测试命名是否为 `shouldXxxWhenYyy` 或 `shouldXxx` 风格？
- [ ] 单个测试是否只验证一个行为？
- [ ] LLM 相关测试是否 mock 了外部调用？
- [ ] 测试中是否避免了硬编码的临时路径？→ 使用 @TempDir

## 输出格式

每次扫描输出：

```
📋 规范审查报告

✅ 通过项: N
⚠️ 需修复: M

[如有问题]
❌ 类别: 命名规约
   文件: src/main/java/com/repordar/anomaly/VagueScoringEngine.java:42
   问题: 方法名 `ExtractDescription` 不符合 lowerCamelCase
   修复: → `extractDescription`
   状态: 已修复 ✓

[最终状态]
✅ 全部通过，可以进行下一步
```

## 执行规则

1. **每次扫描必须覆盖全部 9 大类** — 不跳过任何类别
2. **发现问题立即修复** — 修复后重新运行相关测试确认无回归
3. **循环扫描直到零问题** — 不允许遗留问题进入下一个任务
4. **仅审查变更的代码** — 不审查未修改的历史代码（除非变更引发了连锁问题）
5. **修复时保持 git 原子性** — 规范修复单独提交，不与功能代码混合
