package com.repordar.dto;

import java.util.List;

public record CrossDomainCommitDto(
    String hash,
    String shortHash,
    String author,
    String authorEmail,
    String date,
    String message,
    List<String> modules,
    int filesChanged,
    CommitAnalysisDto analysis
) {}
