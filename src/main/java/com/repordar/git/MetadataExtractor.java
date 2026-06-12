package com.repordar.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Git 提交元数据提取器。
 * <p>
 * 从 JGit Repository 中提取提交日志、统计信息和模块信息，供下游规则引擎使用。
 * 严格遵循 JGit 安全操作手册，正确使用 DiffFormatter 和 EditList 获取行数统计。
 *
 * @author frank
 */
@Slf4j
@Component
public class MetadataExtractor {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String DEV_NULL = "/dev/null";

    private final ModuleDetector moduleDetector;

    public MetadataExtractor(ModuleDetector moduleDetector) {
        this.moduleDetector = moduleDetector;
    }

    /**
     * 从仓库中提取提交元数据。
     *
     * @param repository Git 仓库
     * @param refStr     引用字符串（如 HEAD、refs/heads/main）
     * @return 提交信息列表（按时间倒序，最新的在前）
     */
    public List<CommitInfo> extract(Repository repository, String refStr) {
        List<CommitInfo> result = new ArrayList<>();

        try (Git git = new Git(repository)) {
            // 解析引用
            ObjectId refId = repository.resolve(refStr);
            if (refId == null) {
                log.warn("引用不存在: {}", refStr);
                return result;
            }

            // 遍历提交日志
            Iterable<RevCommit> commits = git.log().add(refId).call();

            for (RevCommit commit : commits) {
                CommitInfo info = extractCommitInfo(repository, commit);
                result.add(info);
            }
        } catch (Exception e) {
            log.error("提取提交元数据失败: {}", e.getMessage(), e);
        }

        return result;
    }

    /**
     * 从单个 RevCommit 提取详细信息。
     *
     * @param repository Git 仓库
     * @param commit     提交对象
     * @return 提交信息
     */
    private CommitInfo extractCommitInfo(Repository repository, RevCommit commit) {
        CommitInfo info = new CommitInfo();

        // 基本信息
        info.setHash(commit.getName());
        info.setShortHash(commit.abbreviate(7).name());
        info.setAuthor(commit.getAuthorIdent().getName());
        info.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
        info.setMessage(commit.getFullMessage());

        // 日期转换
        LocalDateTime date = Instant.ofEpochSecond(commit.getCommitTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        info.setDate(date.format(DATE_FORMATTER));

        // Diff 统计
        try {
            extractDiffStats(repository, commit, info);
        } catch (IOException e) {
            log.warn("提取 diff 统计失败: {}", e.getMessage());
            info.setTotalLines(0);
            info.setFilesChanged(0);
            info.setLinesAdded(0);
            info.setLinesDeleted(0);
            info.setChangedFiles(List.of());
            info.setModules(Set.of());
        }

        return info;
    }

    /**
     * 提取 diff 统计信息。
     * <p>
     * 严格遵循 JGit 安全手册，使用 DiffFormatter + EditList 获取行数统计。
     *
     * @param repository Git 仓库
     * @param commit     提交对象
     * @param info       提交信息对象（会被修改）
     * @throws IOException IO 操作失败
     */
    private void extractDiffStats(Repository repository, RevCommit commit, CommitInfo info) throws IOException {
        List<String> changedFiles = new ArrayList<>();
        int totalAdded = 0;
        int totalDeleted = 0;

        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            formatter.setDetectRenames(true);

            List<DiffEntry> diffs;
            if (commit.getParentCount() > 0) {
                // 有父提交：与父提交比较
                diffs = formatter.scan(commit.getParent(0), commit);
            } else {
                // 首次提交：与空树比较
                diffs = formatter.scan(new EmptyTreeIterator(),
                        new CanonicalTreeParser(null, repository.newObjectReader(), commit.getTree()));
            }

            // 统计每个文件的变动
            for (DiffEntry diff : diffs) {
                String newPath = diff.getNewPath();
                String oldPath = diff.getOldPath();

                // 记录变动的文件路径
                if (!DEV_NULL.equals(newPath)) {
                    changedFiles.add(newPath);
                } else if (!DEV_NULL.equals(oldPath)) {
                    changedFiles.add(oldPath);
                }

                // 获取行数统计（通过 EditList）
                EditList edits = formatter.toFileHeader(diff).toEditList();
                int added = edits.stream().mapToInt(Edit::getLengthB).sum();
                int deleted = edits.stream().mapToInt(Edit::getLengthA).sum();

                totalAdded += added;
                totalDeleted += deleted;
            }
        }

        info.setChangedFiles(changedFiles);
        info.setFilesChanged(changedFiles.size());
        info.setLinesAdded(totalAdded);
        info.setLinesDeleted(totalDeleted);
        info.setTotalLines(totalAdded + totalDeleted);

        // 提取模块信息
        info.setModules(moduleDetector.extractModules(changedFiles));
    }
}
