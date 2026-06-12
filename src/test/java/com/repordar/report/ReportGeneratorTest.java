package com.repordar.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repordar.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReportGenerator 单元测试。
 *
 * @author frank
 */
class ReportGeneratorTest {

    private ReportGenerator generator;
    private ObjectMapper objectMapper;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        this.objectMapper = new ObjectMapper();
        this.generator = new ReportGenerator(objectMapper);
    }

    @Test
    void shouldGenerateHtmlFileWithDataInjection() throws Exception {
        // Given
        String templateHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Test Report</title></head>
            <body>
                <div id="app">
                    <script>const __DATA__ = __INJECT_DATA__;</script>
                </div>
            </body>
            </html>
            """;

        ReportDataDto data = createMinimalReportData();

        // When
        String outputPath = generator.generate(data, tempDir.toString(), templateHtml);

        // Then
        Path outputFile = Path.of(outputPath);
        assertTrue(Files.exists(outputFile), "输出文件应该存在");

        String content = Files.readString(outputFile);
        assertTrue(content.contains("const __DATA__ ="), "应该包含数据注入点");
        // JSON 格式化后可能有空格，所以使用更宽松的匹配
        assertTrue(content.contains("\"name\"") && content.contains("test-repo"), "应该包含序列化的数据");
        assertFalse(content.contains("__INJECT_DATA__"), "占位符应该被替换");
    }

    @Test
    void shouldEscapeScriptTagInJson() throws Exception {
        // Given
        String templateHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <script>const __DATA__ = __INJECT_DATA__;</script>
            </body>
            </html>
            """;

        ReportDataDto data = new ReportDataDto();
        RepositoryInfoDto repo = new RepositoryInfoDto();
        repo.setName("</script><script>alert('xss')</script>");
        data.setRepository(repo);

        // When
        String outputPath = generator.generate(data, tempDir.toString(), templateHtml);

        // Then
        String content = Files.readString(Path.of(outputPath));
        assertTrue(content.contains("<\\/script>"), "应该转义 </script> 标签");
        assertFalse(content.contains("</script>alert"), "不应该包含未转义的 script 标签");
    }

    @Test
    void shouldUseCorrectFileNameFormat() throws Exception {
        // Given
        String templateHtml = "<script>const __DATA__ = __INJECT_DATA__;</script>";
        ReportDataDto data = createMinimalReportData();

        // When
        String outputPath = generator.generate(data, tempDir.toString(), templateHtml);

        // Then
        String fileName = Path.of(outputPath).getFileName().toString();
        // 文件名格式：repordar-{name}-{timestamp}.html，timestamp 为 yyyyMMdd-HHmmss
        assertTrue(fileName.matches("repordar-test-repo-\\d{8}-\\d{6}\\.html"), "文件名格式应该正确");
    }

    @Test
    void shouldCreateOutputDirectoryIfNotExists() throws Exception {
        // Given
        String templateHtml = "<script>const __DATA__ = __INJECT_DATA__;</script>";
        ReportDataDto data = createMinimalReportData();
        Path newDir = tempDir.resolve("subdir").resolve("nested");

        // When
        String outputPath = generator.generate(data, newDir.toString(), templateHtml);

        // Then
        Path outputFile = Path.of(outputPath);
        assertTrue(Files.exists(outputFile), "应该在创建的目录中生成文件");
    }

    @Test
    void shouldFormatJsonWithPrettyPrint() throws Exception {
        // Given
        String templateHtml = "<script>const __DATA__ = __INJECT_DATA__;</script>";
        ReportDataDto data = createMinimalReportData();

        // When
        String outputPath = generator.generate(data, tempDir.toString(), templateHtml);

        // Then
        String content = Files.readString(Path.of(outputPath));
        assertTrue(content.contains("\n"), "JSON 应该格式化为多行");
        assertTrue(content.contains("  "), "JSON 应该包含缩进");
    }

    private ReportDataDto createMinimalReportData() {
        ReportDataDto data = new ReportDataDto();

        RepositoryInfoDto repo = new RepositoryInfoDto();
        repo.setName("test-repo");
        repo.setBranch("main");
        data.setRepository(repo);

        AnalysisMetaDto meta = new AnalysisMetaDto();
        meta.setVersion("1.0.0");
        data.setMeta(meta);

        return data;
    }
}
