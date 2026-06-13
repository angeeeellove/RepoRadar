package com.repordar.cli;

import com.repordar.config.AppProperties;
import com.repordar.pipeline.AnalysisPipeline;
import com.repordar.sse.SseProgressService;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * CLI 分析命令入口，解析参数并驱动分析管线。
 *
 * @author frank
 */
@Slf4j
@Component
@Command(name = "repordar", mixinStandardHelpOptions = true,
         description = "RepoRadar - 团队代码脉搏雷达")
public class AnalyzeCommand implements Callable<Integer> {

    /** 连接失败关键字，用于提示代理配置 */
    private static final String CONNECTION_FAILED_KEYWORD = "connection failed";
    /** 代理 URL 解析后应有的段数（host:port） */
    private static final int PROXY_URL_PARTS_COUNT = 2;

    @Parameters(index = "0", description = "Git 仓库 URL 或本地路径")
    private String repo;

    @Option(names = "--output", description = "报告输出目录", defaultValue = "./reports")
    private String output;

    @Option(names = "--since", description = "分析起始日期 (yyyy-MM-dd)")
    private String since;

    @Option(names = "--until", description = "分析截止日期 (yyyy-MM-dd)")
    private String until;

    @Option(names = "--branch", description = "分析的分支 (默认: 仓库默认分支)")
    private String branch;

    @Option(names = "--llm-api-key", description = "LLM API Key")
    private String llmApiKey;

    @Option(names = "--llm-base-url", description = "LLM API 基础 URL")
    private String llmBaseUrl;

    @Option(names = "--llm-model", description = "LLM 模型名称")
    private String llmModel;

    @Option(names = "--port", description = "SSE 服务端口", defaultValue = "8080")
    private int port;

    @Option(names = "--no-browser", description = "不自动打开浏览器")
    private boolean noBrowser;

    @Option(names = "--proxy", description = "HTTP 代理 (如 http://127.0.0.1:7890)")
    private String proxy;

    private final ApplicationContext context;
    private final AppProperties props;
    private final SseProgressService sseProgressService;

    public AnalyzeCommand(ApplicationContext context, AppProperties props,
                          SseProgressService sseProgressService) {
        this.context = context;
        this.props = props;
        this.sseProgressService = sseProgressService;
    }

    @Override
    public Integer call() {
        String effectiveLlmKey = resolve(llmApiKey, props.getLlm().getApiKey());
        String effectiveLlmModel = resolve(llmModel, props.getLlm().getModelName());

        // Base URL: CLI 参数 > 自动推断（根据模型名匹配厂商）> 配置文件
        String explicitUrl = resolve(llmBaseUrl, null);
        String effectiveLlmUrl = AppProperties.Llm.resolveBaseUrl(effectiveLlmModel, explicitUrl);
        if (effectiveLlmUrl == null) {
            effectiveLlmUrl = props.getLlm().getBaseUrl();
        }

        // 配置 HTTP 代理: CLI 参数 > 环境变量 > git config
        configureProxy(proxy, repo);

        System.out.println("🔍 RepoRadar 开始分析: " + repo);
        if (effectiveLlmKey == null || effectiveLlmKey.isBlank()) {
            System.out.println("⚠️  未配置 LLM API Key，将跳过语义分析");
        } else if (effectiveLlmUrl == null || effectiveLlmUrl.isBlank()) {
            System.out.println("⚠️  无法识别 LLM 厂商，请通过 --llm-base-url 指定 API 地址");
            System.out.println("   支持自动识别: deepseek / gpt / claude / glm / qwen / moonshot");
            System.out.println("   将跳过语义分析");
            effectiveLlmKey = null;
        } else {
            System.out.println("📡 LLM 配置: model=" + effectiveLlmModel + ", url=" + effectiveLlmUrl);
        }

        try {
            // 创建 SSE Emitter（浏览器连接后可接收实时进度）
            sseProgressService.createEmitter();

            // 自动打开浏览器
            if (!noBrowser && Desktop.isDesktopSupported()) {
                try {
                    String url = "http://localhost:" + port;
                    Desktop.getDesktop().browse(URI.create(url));
                    log.info("已打开浏览器: {}", url);
                    // 等待浏览器连接 SSE
                    Thread.sleep(2000);
                } catch (Exception e) {
                    log.warn("无法打开浏览器: {}", e.getMessage());
                }
            }

            AnalysisPipeline pipeline = context.getBean(AnalysisPipeline.class);
            String reportPath = pipeline.execute(
                repo, output, since, until, branch,
                effectiveLlmKey, effectiveLlmUrl, effectiveLlmModel
            );
            System.out.println("✅ 分析完成！报告: " + reportPath);

            // 给 SSE 事件刷新到浏览器
            Thread.sleep(1000);
            return 0;
        } catch (Exception e) {
            log.error("分析失败", e);
            System.err.println("❌ 分析失败: " + e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains(CONNECTION_FAILED_KEYWORD)) {
                System.err.println();
                System.err.println("💡 网络连接失败，可能需要配置代理：");
                System.err.println("   --proxy http://127.0.0.1:7890");
                System.err.println("   或设置环境变量: HTTPS_PROXY=http://127.0.0.1:7890");
            }
            return 2;
        }
    }

    /**
     * 配置 JVM HTTP 代理。
     * <p>
     * 优先级：
     * 1. --proxy CLI 参数
     * 2. HTTPS_PROXY / HTTP_PROXY 环境变量
     * 3. ~/.gitconfig 中的 http.&lt;url&gt;.proxy 配置（按 URL 模式匹配）
     *
     * @param cliProxy CLI 传入的代理地址（可为 null）
     * @param repoUrl  目标仓库 URL（用于匹配 gitconfig 中的 URL 条件代理）
     */
    private void configureProxy(String cliProxy, String repoUrl) {
        String proxyUrl = resolve(cliProxy, null);

        // 优先级 2：环境变量
        if (proxyUrl == null) {
            proxyUrl = resolve(System.getenv("HTTPS_PROXY"),
                    resolve(System.getenv("HTTP_PROXY"),
                            resolve(System.getenv("https_proxy"),
                                    System.getenv("http_proxy"))));
        }

        // 优先级 3：从 git config 读取（仅远程仓库）
        if (proxyUrl == null && isRemoteRepo(repoUrl)) {
            proxyUrl = readProxyFromGitConfig(repoUrl);
        }

        if (proxyUrl == null || proxyUrl.isBlank()) {
            return;
        }

        String source = cliProxy != null && !cliProxy.isBlank() ? "CLI 参数"
                : "自动检测";
        applyProxy(proxyUrl, source);
    }

    /**
     * 从 ~/.gitconfig 读取匹配目标 URL 的代理配置。
     * <p>
     * Git 支持按 URL 设置代理，格式为：
     * <pre>
     * [http "https://github.com/"]
     *     proxy = http://127.0.0.1:7890
     * </pre>
     * 匹配规则：目标 URL 以 subsection 为前缀则命中，最长匹配优先。
     * 也支持全局代理：[http] proxy = http://...
     */
    private String readProxyFromGitConfig(String repoUrl) {
        try {
            FileBasedConfig gitConfig = SystemReader.getInstance()
                    .openUserConfig(null, FS.detect());
            gitConfig.load();

            // 1. 先查找 URL 匹配的代理（http.<url-pattern>.proxy）
            Set<String> subsections = gitConfig.getSubsections("http");
            String bestMatch = null;
            String bestProxy = null;

            for (String subsection : subsections) {
                if (repoUrl.startsWith(subsection)) {
                    String proxy = gitConfig.getString("http", subsection, "proxy");
                    if (proxy != null && !proxy.isBlank()) {
                        // 选最长匹配（更精确的优先）
                        if (bestMatch == null || subsection.length() > bestMatch.length()) {
                            bestMatch = subsection;
                            bestProxy = proxy;
                        }
                    }
                }
            }

            if (bestProxy != null) {
                log.info("从 git 配置读取到代理: http.{}.proxy={}", bestMatch, bestProxy);
                return bestProxy;
            }

            // 2. 兜底：全局 http.proxy（无 URL 条件）
            String globalProxy = gitConfig.getString("http", null, "proxy");
            if (globalProxy != null && !globalProxy.isBlank()) {
                log.info("从 git 配置读取到全局代理: http.proxy={}", globalProxy);
                return globalProxy;
            }

        } catch (Exception e) {
            log.debug("读取 gitconfig 代理配置失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解析代理 URL 并设置为 JVM 系统属性，使 JGit 和 Java HTTP 客户端走代理。
     */
    private void applyProxy(String proxyUrl, String source) {
        try {
            String stripped = proxyUrl.replaceFirst("^https?://", "");
            String[] parts = stripped.split(":");
            if (parts.length != PROXY_URL_PARTS_COUNT) {
                log.warn("代理格式无效（需 http://host:port）: {}", proxyUrl);
                return;
            }
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", String.valueOf(port));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", String.valueOf(port));

            System.out.println("🌐 代理已配置（" + source + "）: " + host + ":" + port);
        } catch (NumberFormatException e) {
            log.warn("代理端口无效: {}", proxyUrl);
        }
    }

    /**
     * 判断是否为远程仓库 URL。
     */
    private boolean isRemoteRepo(String repoRef) {
        if (repoRef == null) {
            return false;
        }
        return repoRef.startsWith("http://")
                || repoRef.startsWith("https://")
                || repoRef.startsWith("git@")
                || repoRef.endsWith(".git");
    }

    private String resolve(String cliValue, String configValue) {
        return (cliValue != null && !cliValue.isBlank()) ? cliValue : configValue;
    }
}
