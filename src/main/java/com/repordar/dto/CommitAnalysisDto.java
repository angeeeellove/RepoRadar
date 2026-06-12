package com.repordar.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM Map 阶段分析结果，需从 LLM JSON 反序列化。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommitAnalysisDto {

    private String intent;
    private List<String> tags;
    @JsonAlias("risk_level")
    private String riskLevel;
    @JsonAlias("message_quality")
    private String messageQuality;
    @JsonAlias("quality_reason")
    private String qualityReason;
}
