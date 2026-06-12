package com.repordar.dto;

import java.util.List;

public record ReportDataDto(
    RepositoryInfoDto repository,
    List<AuthorStatsDto> authors,
    List<ModuleStatsDto> modules,
    AnomalyGroupDto anomalies,
    GlobalInsightDto globalInsight,
    ActivityHeatmapDto activityHeatmap,
    AnalysisMetaDto meta
) {}
