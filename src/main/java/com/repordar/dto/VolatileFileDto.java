package com.repordar.dto;

import java.util.List;

public record VolatileFileDto(
    String path,
    int changeCount,
    int windowDays,
    List<String> contributors,
    int linesChanged
) {}
