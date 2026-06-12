package com.repordar;

import com.repordar.cli.AnalyzeCommand;
import com.repordar.config.AppProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import picocli.CommandLine;

/**
 * RepoRadar 应用入口。
 *
 * @author frank
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class RepoRadarApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(RepoRadarApplication.class, args)));
    }

    /**
     * 注册 Picocli CommandLineRunner，驱动 CLI 命令执行。
     */
    @Bean
    public CommandLineRunner commandLineRunner(AnalyzeCommand analyzeCommand) {
        return args -> {
            int exitCode = new CommandLine(analyzeCommand).execute(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        };
    }
}
