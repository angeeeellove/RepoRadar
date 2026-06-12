package com.repordar.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跨域提交 DTO。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrossDomainCommitDto {

    private String hash;
    private String shortHash;
    private String author;
    private String authorEmail;
    private String date;
    private String message;
    private List<String> modules;
    private int filesChanged;
    private CommitAnalysisDto analysis;
}
