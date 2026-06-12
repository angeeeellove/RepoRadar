package com.repordar.dto;

public record AnalysisMetaDto(
    String analyzedAt,
    boolean llmEnabled,
    String llmModel,
    long analysisDurationMs,
    String version
) {}
