# LLM 集成防线

> LLM 输出不可信。与 LLM 交互的每一行代码都必须做防御性编程。

## 1. Prompt 模板化

- 所有 Prompt 定义为 Java 常量或 `static final` 字符串，**禁止在业务代码中拼接长字符串**
- Prompt 末尾必须包含：`"只返回 JSON，不要其他内容。"`
- 系统提示词明确要求 LLM 返回纯 JSON，不带 Markdown 格式化标记

## 2. 响应清洗（三步防线）

LLM 响应可能包含各种垃圾字符，必须按以下顺序清洗：

```java
/**
 * 从 LLM 响应中提取有效 JSON。
 * 三步防线：① 去除 markdown 代码块 ② 提取花括号范围 ③ 返回原始文本兜底
 */
private String extractJson(String response) {
    if (response == null || response.isBlank()) {
        return "{}";
    }

    // 防线 1：去除 ```json ... ``` 包裹
    int codeStart = response.indexOf("```json");
    if (codeStart >= 0) {
        int jsonStart = response.indexOf('\n', codeStart) + 1;
        int jsonEnd = response.indexOf("```", jsonStart);
        if (jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd).trim();
        }
    }

    // 防线 2：提取第一个 { 到最后一个 } 之间的内容
    int braceStart = response.indexOf('{');
    int braceEnd = response.lastIndexOf('}');
    if (braceStart >= 0 && braceEnd > braceStart) {
        return response.substring(braceStart, braceEnd + 1);
    }

    // 防线 3：兜底返回空 JSON
    return "{}";
}
```

## 3. 反序列化降级策略

```java
// 每个 LLM 调用点都必须遵循此模式
try {
    String json = extractJson(response);
    CommitAnalysisDto dto = mapper.readValue(json, CommitAnalysisDto.class);
    return sanitizeDto(dto);  // 枚举值校验
} catch (JsonProcessingException e) {
    log.warn("LLM JSON 解析失败，降级为默认值: {}", e.getMessage());
    return new CommitAnalysisDto("解析失败", List.of("CHORE"), "LOW", "VAGUE", null);
}
```

**降级铁律：**
- 解析失败 → 返回带默认值的 DTO，**绝不抛出异常**
- LLM API 超时/网络错误 → 返回 null 或空列表
- 所有 LLM 降级输出 WARN 级别日志，包含失败原因
- 系统不崩溃，基线功能可用

## 4. Token 截断

喂给 LLM 的文本（特别是 Diff 内容）必须强制截断：

```java
private static final int MAX_DIFF_CHARS = 2000;

private String truncateDiff(String diff) {
    if (diff == null || diff.length() <= MAX_DIFF_CHARS) {
        return diff;
    }
    return diff.substring(0, MAX_DIFF_CHARS) + "\n... (截断)";
}
```

- 单次 Diff 文本超过 **2000 字符** → 强制截断 + 追加 `"... (截断)"`
- 截断在 LLM 调用前执行，不是在异常处理时

## 5. API Key 安全

- 禁止在日志中输出 API Key
- 日志脱敏：`log.info("调用 LLM: model={}, url={}", model, maskUrl(baseUrl));`
- 禁止 API Key 出现在源码中
