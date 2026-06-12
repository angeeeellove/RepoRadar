package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 报告顶层 DTO，对应完整 JSON 数据。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportDataDto {

    private RepositoryInfoDto repository;
    private List<AuthorStatsDto> authors;
    private List<ModuleStatsDto> modules;
    private AnomalyGroupDto anomalies;
    private GlobalInsightDto globalInsight;
    private ActivityHeatmapDto activityHeatmap;
    private AnalysisMetaDto meta;
}
