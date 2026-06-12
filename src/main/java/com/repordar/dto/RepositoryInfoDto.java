package com.repordar.dto;

public record RepositoryInfoDto(
    String name,
    String url,
    String branch,
    AnalysisPeriod analysisPeriod,
    int totalCommits,
    int totalAuthors,
    int totalFiles
) {
    public record AnalysisPeriod(String since, String until) {}
}
