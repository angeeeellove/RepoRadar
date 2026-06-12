package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryInfoDto {

    private String name;
    private String url;
    private String branch;
    private AnalysisPeriod analysisPeriod;
    private int totalCommits;
    private int totalAuthors;
    private int totalFiles;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalysisPeriod {
        private String since;
        private String until;
    }
}
