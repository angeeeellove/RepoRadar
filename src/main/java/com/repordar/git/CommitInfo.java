package com.repordar.git;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 提交信息数据对象，由 MetadataExtractor 提取后供下游规则引擎使用。
 *
 * @author frank
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommitInfo {

    /** 完整 commit hash */
    private String hash;
    /** 短 hash（前 7 位） */
    private String shortHash;
    /** 作者名 */
    private String author;
    /** 作者邮箱 */
    private String authorEmail;
    /** 提交日期（ISO 格式） */
    private String date;
    /** 提交信息 */
    private String message;
    /** 总变动行数（added + deleted） */
    private int totalLines;
    /** 变动文件数 */
    private int filesChanged;
    /** 变动文件路径列表 */
    private List<String> changedFiles;
    /** 涉及的模块集合 */
    private Set<String> modules;
    /** 新增行数 */
    private int linesAdded;
    /** 删除行数 */
    private int linesDeleted;
}
