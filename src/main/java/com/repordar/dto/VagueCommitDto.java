package com.repordar.dto;

import java.util.List;

public record VagueCommitDto(
    String hash,
    String shortHash,
    String author,
    String authorEmail,
    String date,
    String message,
    String vagueReason,
    Integer score,
    CommitAnalysisDto analysis
) {}
