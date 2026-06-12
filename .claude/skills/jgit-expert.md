# JGit 安全操作手册

> CC 对 JGit API 极其不熟悉，极易幻觉出不存在的方法。编写任何 JGit 代码前必须参照本手册。

## 1. 核心原则

- **不确定就不写** — 如果不确定某个 JGit 方法是否存在，先查文档或提问
- **严禁 `Runtime.exec("git ...")`** — 必须使用原生 JGit API
- **资源必须释放** — `RevWalk`、`DiffFormatter`、`Repository`、`Git` 必须在 try-with-resources 或 finally 中关闭

## 2. 正确的 Diff 提取样板

```java
// 获取某次 commit 的 diff 列表和统计
try (Repository repo = ...) {
    try (Git git = new Git(repo)) {
        // 获取 RevCommit
        ObjectId commitId = repo.resolve(commitHash);
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(commitId);

            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(repo);
                df.setDiffComparator(RawTextComparator.DEFAULT);
                df.setDetectRenames(true);

                if (commit.getParentCount() > 0) {
                    // 有 parent：比较 parent 和当前 commit
                    List<DiffEntry> diffs = df.scan(commit.getParent(0), commit);
                    for (DiffEntry diff : diffs) {
                        String oldPath = diff.getOldPath();   // 旧路径
                        String newPath = diff.getNewPath();   // 新路径
                        // 注意：DiffEntry 没有直接的 getAddedLines()/getDeletedLines()
                        // 必须通过 EditList 获取行数统计：
                        EditList edits = df.toFileHeader(diff).toEditList();
                        int added = edits.stream().mapToInt(Edit::getLengthB).sum();
                        int deleted = edits.stream().mapToInt(Edit::getLengthA).sum();
                    }
                } else {
                    // 首次 commit：与空树比较
                    List<DiffEntry> diffs = df.scan(new EmptyTreeIterator(),
                            new CanonicalTreeParser(null, repo.newObjectReader(), commit.getTree()));
                }
            }
        }
    }
}
```

## 3. 正确的提交日志遍历样板

```java
try (Repository repo = ...) {
    try (Git git = new Git(repo)) {
        LogCommand logCmd = git.log();
        // 指定分支
        if (branch != null) {
            ObjectId branchId = repo.resolve(branch);
            if (branchId != null) {
                logCmd.add(branchId);
            }
        } else {
            // HEAD
            ObjectId head = repo.resolve("HEAD");
            if (head != null) {
                logCmd.add(head);
            }
        }

        for (RevCommit commit : logCmd.call()) {
            String message = commit.getFullMessage();    // 完整 commit message
            String author = commit.getAuthorIdent().getName();
            String email = commit.getAuthorIdent().getEmailAddress();
            int commitTime = commit.getCommitTime();     // Unix timestamp (秒)
            // 日期转换：
            LocalDateTime date = Instant.ofEpochSecond(commitTime)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
    }
}
```

## 4. 常见 API 幻觉黑名单

以下方法/调用**不存在**，严禁使用：

| ❌ 幻觉 API | ✅ 正确替代 |
|---|---|
| `DiffEntry.getAddedLines()` | `df.toFileHeader(diff).toEditList()` → 统计 Edit |
| `DiffEntry.getDeletedLines()` | 同上 |
| `git.getLog()` | `git.log()` |
| `commit.getDiff()` | `df.scan(parent, commit)` |
| `repository.getBranches()` | `git.branchList().call()` |
| `commit.getAuthor()` | `commit.getAuthorIdent().getName()` |

## 5. 强制 TDD

任何 JGit 数据提取方法，必须：
1. 先写单测（用 `@TempDir` 创建临时 Git 仓库）
2. 测试红 → 实现代码 → 测试绿
3. 合并到主代码
