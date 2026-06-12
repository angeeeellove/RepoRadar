package com.repordar.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置属性，通过 setter 绑定 application.yml。
 *
 * @author frank
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "repordar")
public class AppProperties {

    private Anomaly anomaly;
    private Llm llm;
    private Report report;

    @Getter
    @Setter
    public static class Anomaly {
        private int giantCommitThreshold;
        private int volatileWindowDays;
        private int volatileThreshold;
        private int crossDomainThreshold;
    }

    @Getter
    @Setter
    public static class Llm {
        private String apiKey;
        private String baseUrl;
        private String modelName;

        /**
         * 主流 LLM 厂商模型名关键词 → Base URL 映射。
         * 按匹配优先级排列，先匹配到的优先。
         */
        private static final Map<List<String>, String> PROVIDER_URL_MAP = new LinkedHashMap<>();

        static {
            // DeepSeek
            PROVIDER_URL_MAP.put(
                    Arrays.asList("deepseek"),
                    "https://api.deepseek.com/v1");
            // OpenAI
            PROVIDER_URL_MAP.put(
                    Arrays.asList("gpt", "o1-", "o3-", "o4-"),
                    "https://api.openai.com/v1");
            // Anthropic Claude
            PROVIDER_URL_MAP.put(
                    Arrays.asList("claude"),
                    "https://api.anthropic.com/v1");
            // 智谱 GLM
            PROVIDER_URL_MAP.put(
                    Arrays.asList("glm", "chatglm"),
                    "https://open.bigmodel.cn/api/paas/v4");
            // 阿里通义千问
            PROVIDER_URL_MAP.put(
                    Arrays.asList("qwen", "qwq"),
                    "https://dashscope.aliyuncs.com/compatible-mode/v1");
            // Moonshot / Kimi
            PROVIDER_URL_MAP.put(
                    Arrays.asList("moonshot", "kimi"),
                    "https://api.moonshot.cn/v1");
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * 根据模型名自动推断 LLM Base URL。
         * <p>
         * 解析优先级：
         * 1. 如果用户显式传了 explicitBaseUrl，直接使用（覆盖自动推断）
         * 2. 如果模型名包含已知厂商标识，返回对应的 Base URL
         * 3. 都无法匹配时返回 null
         *
         * @param modelName        完整模型产品名（如 GLM-4.7、deepseek-chat、gpt-4o）
         * @param explicitBaseUrl  用户显式指定的 URL（CLI --llm-base-url），可为 null
         * @return 解析后的 Base URL，无法解析时返回 null
         */
        public static String resolveBaseUrl(String modelName, String explicitBaseUrl) {
            // 优先级 1：显式指定的 URL 直接使用
            if (explicitBaseUrl != null && !explicitBaseUrl.isBlank()) {
                return explicitBaseUrl;
            }

            // 优先级 2：从模型名中匹配厂商标识
            if (modelName == null || modelName.isBlank()) {
                return null;
            }

            String lowerModel = modelName.toLowerCase();
            for (Map.Entry<List<String>, String> entry : PROVIDER_URL_MAP.entrySet()) {
                for (String keyword : entry.getKey()) {
                    if (lowerModel.contains(keyword)) {
                        return entry.getValue();
                    }
                }
            }

            // 优先级 3：无法匹配
            return null;
        }
    }

    @Getter
    @Setter
    public static class Report {
        private String outputDir;
    }
}
