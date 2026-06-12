package com.repordar.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CommitAnalysisDto(
    String intent,
    List<String> tags,
    @JsonProperty("risk_level") String riskLevel,
    @JsonProperty("message_quality") String messageQuality,
    @JsonProperty("quality_reason") String qualityReason
) {}
