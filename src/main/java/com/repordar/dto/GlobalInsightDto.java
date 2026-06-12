package com.repordar.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 全局洞察 DTO。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GlobalInsightDto {

    private String summary;
    private List<String> recommendations;
    @JsonAlias("health_score")
    private int healthScore;
}
