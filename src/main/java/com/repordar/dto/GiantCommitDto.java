package com.repordar.dto;

import java.util.List;

public record GiantCommitDto(
    String hash,
    String shortHash,
    String author,
    String authorEmail,
    String date,
    String message,
    int totalLines,
    int filesChanged,
    List<String> modules,
    CommitAnalysisDto analysis
) {}
