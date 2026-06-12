package com.repordar.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 分析进度 SSE 控制器 + 报告文件服务。
 * <p>
 * 端点：
 * - GET / → 实时进度页面
 * - GET /api/sse → SSE 事件流
 * - GET /report/{filename} → 报告文件
 *
 * @author frank
 */
@Slf4j
@RestController
public class AnalysisController {

    private static final String REPORTS_DIR = "./reports";
    private static final String TEST_REPORTS_DIR = "./test-reports";
    /** 路径遍历攻击检测字符 */
    private static final String[] PATH_TRAVERSAL_MARKS = {"..", "/", "\\"};

    /**
     * 清理文件名中的 CRLF 字符（防止日志注入）。
     */
    private static String sanitizeForLog(String input) {
        if (input == null) {
            return "null";
        }
        return input.replace('\n', '_').replace('\r', '_');
    }

    private final SseProgressService sseProgressService;

    public AnalysisController(SseProgressService sseProgressService) {
        this.sseProgressService = sseProgressService;
    }

    /**
     * SSE 事件流端点。
     * 返回 CLI 创建的 SseEmitter，浏览器通过 EventSource 连接接收实时进度。
     *
     * @return SseEmitter 实例
     */
    @GetMapping("/api/sse")
    public SseEmitter sseStream() {
        SseEmitter emitter = sseProgressService.getEmitter();
        if (emitter == null) {
            // 如果 emitter 还未创建，创建一个新的
            emitter = sseProgressService.createEmitter();
            log.info("SSE 客户端连接，创建新 emitter");
        } else {
            log.info("SSE 客户端连接，复用已有 emitter");
        }
        return emitter;
    }

    /**
     * 进度页面。
     * 返回 classpath:static/progress.html。
     *
     * @return 进度页面 HTML
     * @throws IOException IO 异常
     */
    @GetMapping("/")
    public ResponseEntity<String> progressPage() throws IOException {
        Resource resource = new ClassPathResource("static/progress.html");
        String html;
        try (var is = resource.getInputStream()) {
            html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    /**
     * 报告文件服务。
     * 从 reports 目录读取生成的 HTML 报告文件，通过 HTTP 提供（避免 file:// 跨域问题）。
     *
     * @param filename 报告文件名
     * @return 报告 HTML 内容
     * @throws IOException IO 异常
     */
    @GetMapping("/report/{filename}")
    public ResponseEntity<String> serveReport(@PathVariable String filename) throws IOException {
        // 尝试从两个可能的输出目录读取
        Path reportFile = findReportFile(filename);
        if (reportFile == null) {
            return ResponseEntity.notFound().build();
        }

        String content = Files.readString(reportFile, StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8")
                .body(content);
    }

    /**
     * 在多个可能的输出目录中查找报告文件。
     */
    private Path findReportFile(String filename) {
        // 安全校验：防止路径遍历
        for (String mark : PATH_TRAVERSAL_MARKS) {
            if (filename.contains(mark)) {
                log.warn("报告文件名包含非法字符: {}", sanitizeForLog(filename));
                return null;
            }
        }

        String[] dirs = {REPORTS_DIR, TEST_REPORTS_DIR};
        for (String dir : dirs) {
            Path path = Path.of(dir, filename);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }
}
