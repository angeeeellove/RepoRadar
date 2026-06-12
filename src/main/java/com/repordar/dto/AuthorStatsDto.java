package com.repordar.dto;

import java.util.List;

public record AuthorStatsDto(
    String name,
    String email,
    int commitCount,
    int linesAdded,
    int linesDeleted,
    int[] activeHourDistribution,
    int[] activeDayDistribution,
    String peakHours,
    List<String> primaryModules,
    String profile
) {}
