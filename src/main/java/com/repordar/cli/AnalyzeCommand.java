package com.repordar.cli;

import com.repordar.config.AppProperties;
import com.repordar.pipeline.AnalysisPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

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

    @Parameters(index = "0", description = "Git 仓库 URL 或本地路径")
    private String repo;

    @Option(names = "--output", description = "报告输出目录", defaultValue = "./reports")
    private String output;

    @Option(names = "--since", description = "分析起始日期 (yyyy-MM-dd)")
    private String since;

    @Option(names = "--until", description = "分析截止日期 (yyyy-MM-dd)")
    private String until;

    @Option(names = "--branch", description = "分析的分支")
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

    private final ApplicationContext context;
    private final AppProperties props;

    public AnalyzeCommand(ApplicationContext context, AppProperties props) {
        this.context = context;
        this.props = props;
    }

    @Override
    public Integer call() {
        String effectiveLlmKey = resolve(llmApiKey, props.getLlm().getApiKey());
        String effectiveLlmUrl = resolve(llmBaseUrl, props.getLlm().getBaseUrl());
        String effectiveLlmModel = resolve(llmModel, props.getLlm().getModelName());

        System.out.println("🔍 RepoRadar 开始分析: " + repo);
        if (effectiveLlmKey == null || effectiveLlmKey.isBlank()) {
            System.out.println("⚠️  未配置 LLM API Key，将跳过语义分析");
        }

        try {
            AnalysisPipeline pipeline = context.getBean(AnalysisPipeline.class);
            String reportPath = pipeline.execute(
                repo, output, since, until, branch,
                effectiveLlmKey, effectiveLlmUrl, effectiveLlmModel
            );
            System.out.println("✅ 分析完成！报告: " + reportPath);
            return 0;
        } catch (Exception e) {
            log.error("分析失败", e);
            System.err.println("❌ 分析失败: " + e.getMessage());
            return 2;
        } finally {
            SpringApplication.exit(context, () -> 0);
        }
    }

    private String resolve(String cliValue, String configValue) {
        return (cliValue != null && !cliValue.isBlank()) ? cliValue : configValue;
    }
}
