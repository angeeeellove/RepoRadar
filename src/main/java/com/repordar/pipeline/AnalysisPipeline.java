package com.repordar.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 分析管线编排器，串联完整分析流程。
 * 各阶段将在后续任务中逐步实现。
 *
 * @author frank
 */
@Slf4j
@Component
public class AnalysisPipeline {

    public String execute(String repoRef, String outputDir, String since, String until,
                          String branch, String llmKey, String llmUrl, String llmModel) throws Exception {
        throw new UnsupportedOperationException("管线尚未实现，将在后续任务中完成");
    }
}
