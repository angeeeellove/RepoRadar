package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模块统计 DTO。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModuleStatsDto {

    private String name;
    private int commitCount;
    private int linesChanged;
    private List<String> topContributors;
    private String insight;
}
