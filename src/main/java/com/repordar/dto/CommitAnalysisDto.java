package com.repordar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM Map 阶段分析结果，需从 LLM JSON 反序列化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CommitAnalysisDto {

    private String intent;
    private List<String> tags;
    @JsonProperty("risk_level")
    private String riskLevel;
    @JsonProperty("message_quality")
    private String messageQuality;
    @JsonProperty("quality_reason")
    private String qualityReason;
}
