package com.repordar.dto;

import java.util.List;

public record GlobalInsightDto(
    String summary,
    List<String> recommendations,
    int healthScore
) {}
