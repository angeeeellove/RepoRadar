package com.repordar.dto;

import java.util.List;

public record ModuleStatsDto(
    String name,
    int commitCount,
    int linesChanged,
    List<String> topContributors,
    String insight
) {}
