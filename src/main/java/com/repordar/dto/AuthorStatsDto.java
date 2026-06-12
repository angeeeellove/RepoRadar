package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorStatsDto {

    private String name;
    private String email;
    private int commitCount;
    private int linesAdded;
    private int linesDeleted;
    private int[] activeHourDistribution;
    private int[] activeDayDistribution;
    private String peakHours;
    private List<String> primaryModules;
    private String profile;
}
