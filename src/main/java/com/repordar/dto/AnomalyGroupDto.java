package com.repordar.dto;

import java.util.List;

public record AnomalyGroupDto(
    List<GiantCommitDto> giantCommits,
    List<VolatileFileDto> volatileFiles,
    List<CrossDomainCommitDto> crossDomainCommits,
    List<VagueCommitDto> vagueCommits
) {}
