package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 异常检测分组 DTO。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyGroupDto {

    private List<GiantCommitDto> giantCommits;
    private List<VolatileFileDto> volatileFiles;
    private List<CrossDomainCommitDto> crossDomainCommits;
    private List<VagueCommitDto> vagueCommits;
}
