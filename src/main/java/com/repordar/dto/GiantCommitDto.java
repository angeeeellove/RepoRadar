package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 巨型提交 DTO。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GiantCommitDto {

    private String hash;
    private String shortHash;
    private String author;
    private String authorEmail;
    private String date;
    private String message;
    private int totalLines;
    private int filesChanged;
    private List<String> modules;
    private CommitAnalysisDto analysis;
}
