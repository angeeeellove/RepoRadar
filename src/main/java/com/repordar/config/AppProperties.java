package com.repordar.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置属性，通过 setter 绑定 application.yml。
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

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    @Getter
    @Setter
    public static class Report {
        private String outputDir;
    }
}
