package com.repordar.dto;

import java.util.List;

public record ActivityHeatmapDto(
    List<HeatmapPoint> data,
    int maxCount
) {}
