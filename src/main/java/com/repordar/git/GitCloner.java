package com.repordar.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Git 仓库克隆与清理组件。
 * <p>
 * 支持远程 URL（bare 克隆）和本地路径两种方式。
 * 远程克隆到系统临时目录，分析完毕自动清理。
 * 内置超时和重试机制，应对网络不稳定环境。
 *
 * @author frank
 */
@Slf4j
@Component
public class GitCloner {

    /** 克隆超时（秒） */
    private static final int CLONE_TIMEOUT_SECONDS = 120;
    /** 最大重试次数 */
    private static final int MAX_RETRIES = 3;
    /** 重试间隔基数（毫秒），实际间隔 = base * retryCount */
    private static final long RETRY_DELAY_MS = 3000;

    /**
     * 克隆或打开仓库。
     *
     * @param repoRef 远程 URL 或本地路径
     * @return 克隆结果，包含 Repository 对象和路径信息
     * @throws GitAPIException Git 操作失败
     * @throws IOException     IO 操作失败
     */
    public ClonedRepo cloneOrOpen(String repoRef) throws GitAPIException, IOException {
        if (isRemoteUrl(repoRef)) {
            return cloneRemote(repoRef);
        }
        return openLocal(repoRef);
    }

    /**
     * 清理克隆的仓库资源。
     * 远程克隆的临时目录会被删除，本地仓库仅关闭不删除。
     *
     * @param repo 克隆结果
     */
    public void cleanup(ClonedRepo repo) {
        if (repo == null) {
            return;
        }
        try {
            repo.getRepository().close();
        } catch (Exception e) {
            log.warn("关闭 Repository 失败: {}", e.getMessage());
        }
        if (repo.isTemp()) {
            deleteTempDir(repo.getPath());
        }
    }

    private boolean isRemoteUrl(String repoRef) {
        return repoRef.startsWith("http://")
                || repoRef.startsWith("https://")
                || repoRef.startsWith("git@")
                || repoRef.endsWith(".git");
    }

    /**
     * 克隆远程仓库，带超时和重试。
     * 使用 bare 模式（仅 .git 元数据，不含工作目录），减小传输量。
     */
    private ClonedRepo cloneRemote(String url) throws GitAPIException, IOException {
        Path tempDir = Files.createTempDirectory("repordar-");
        log.info("克隆远程仓库: {} -> {}", url, tempDir);

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("克隆尝试 {}/{}: {}", attempt, MAX_RETRIES, url);

                Git git = Git.cloneRepository()
                        .setURI(url)
                        .setDirectory(tempDir.toFile())
                        .setBare(true)
                        .setCloneAllBranches(false)
                        .setTimeout(CLONE_TIMEOUT_SECONDS)
                        .call();

                Repository repository = git.getRepository();
                // 不调用 git.close()：它会同时 close Repository，
                // 而 cleanup() 还会再 close 一次导致警告。
                // Repository 由 cleanup() 统一关闭。

                log.info("克隆成功: {}", url);
                return new ClonedRepo(repository, tempDir, true);

            } catch (Exception e) {
                lastException = e;
                log.warn("克隆失败 (尝试 {}/{}): {}", attempt, MAX_RETRIES, e.getMessage());

                if (attempt < MAX_RETRIES) {
                    long delay = RETRY_DELAY_MS * attempt;
                    log.info("等待 {}ms 后重试...", delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("克隆被中断", ie);
                    }
                }
            }
        }

        // 所有重试都失败，清理临时目录
        deleteTempDir(tempDir);
        throw new IOException(
                "克隆仓库失败（已重试 " + MAX_RETRIES + " 次）: " + url + " — "
                        + (lastException != null ? lastException.getMessage() : "未知错误"),
                lastException);
    }

    private ClonedRepo openLocal(String path) throws IOException {
        Path localPath = Path.of(path).toAbsolutePath();
        log.info("打开本地仓库: {}", localPath);

        Repository repository = Git.open(localPath.toFile()).getRepository();
        return new ClonedRepo(repository, localPath, false);
    }

    private void deleteTempDir(Path path) {
        try {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            log.info("已清理临时目录: {}", path);
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}", e.getMessage());
        }
    }

    /**
     * 克隆结果，封装 Repository 和路径信息。
     */
    public static class ClonedRepo {
        private final Repository repository;
        private final Path path;
        private final boolean temp;

        public ClonedRepo(Repository repository, Path path, boolean temp) {
            this.repository = repository;
            this.path = path;
            this.temp = temp;
        }

        public Repository getRepository() { return repository; }
        public Path getPath() { return path; }
        public boolean isTemp() { return temp; }
    }
}
