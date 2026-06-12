package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisMetaDto {

    private String analyzedAt;
    private boolean llmEnabled;
    private String llmModel;
    private long analysisDurationMs;
    private String version;
}
