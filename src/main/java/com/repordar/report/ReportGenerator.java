package com.repordar.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.ReportDataDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报告生成器，负责将数据注入 HTML 模板并生成报告文件。
 *
 * @author frank
 */
public class ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);
    private static final String PLACEHOLDER = "__INJECT_DATA__";
    private static final String FILENAME_FORMAT = "repordar-%s-%s.html";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;

    /**
     * 构造报告生成器。
     *
     * @param ObjectMapper 全局共享的 JSON 序列化器
     */
    public ReportGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成报告 HTML 文件。
     *
     * @param data 报告数据
     * @param outputDir 输出目录
     * @param templateHtml HTML 模板内容
     * @return 生成的 HTML 文件绝对路径
     * @throws IOException 文件操作失败时抛出
     */
    public String generate(ReportDataDto data, String outputDir, String templateHtml) throws IOException {
        if (data == null) {
            throw new IllegalArgumentException("报告数据不能为 null");
        }
        if (data.getRepository() == null || data.getRepository().getName() == null) {
            throw new IllegalArgumentException("仓库名称不能为 null");
        }

        // 序列化数据为 JSON 字符串
        String jsonData = serializeToJson(data);

        // 转义 </script> 标签防止 XSS
        String escapedJson = escapeScriptTag(jsonData);

        // 替换模板占位符
        String htmlContent = templateHtml.replace(PLACEHOLDER, escapedJson);

        // 确保输出目录存在
        Path outputDirPath = Paths.get(outputDir);
        Files.createDirectories(outputDirPath);

        // 生成文件名
        String fileName = generateFileName(data.getRepository().getName());
        Path outputFile = outputDirPath.resolve(fileName);

        // 写入文件
        Files.writeString(outputFile, htmlContent);

        log.info("报告已生成: {}", outputFile);
        return outputFile.toAbsolutePath().toString();
    }

    /**
     * 将报告数据序列化为格式化的 JSON 字符串。
     */
    private String serializeToJson(ReportDataDto data) throws IOException {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            throw new IOException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 转义 </script> 标签为 <\/script> 防止 HTML 注入攻击。
     */
    private String escapeScriptTag(String json) {
        return json.replace("</script>", "<\\/script>");
    }

    /**
     * 生成报告文件名。
     */
    private String generateFileName(String repoName) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        // 清理仓库名中的特殊字符
        String safeName = repoName.replaceAll("[^a-zA-Z0-9_-]", "-");
        return String.format(FILENAME_FORMAT, safeName, timestamp);
    }
}
