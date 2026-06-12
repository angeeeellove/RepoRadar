package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模糊提交 DTO。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VagueCommitDto {

    private String hash;
    private String shortHash;
    private String author;
    private String authorEmail;
    private String date;
    private String message;
    private String vagueReason;
    private Integer score;
    private CommitAnalysisDto analysis;
}
