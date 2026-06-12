package com.repordar.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetadataExtractor 单元测试。
 * <p>
 * 使用 @TempDir 创建临时 Git 仓库进行测试，验证元数据提取的正确性。
 *
 * @author frank
 */
class MetadataExtractorTest {

    @TempDir
    Path tempDir;

    private Git git;
    private ModuleDetector moduleDetector;
    private MetadataExtractor extractor;

    @BeforeEach
    void setUp() throws GitAPIException, IOException {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        moduleDetector = new ModuleDetector();
        extractor = new MetadataExtractor(moduleDetector);
    }

    @Test
    void shouldExtractBasicCommitInfo() throws GitAPIException, IOException {
        // Given: 创建一个提交
        createCommit("Alice", "alice@example.com", "Initial commit", "README.md", "# Hello World");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 验证基本信息
        assertEquals(1, commits.size());
        CommitInfo commit = commits.get(0);
        assertEquals("Alice", commit.getAuthor());
        assertEquals("alice@example.com", commit.getAuthorEmail());
        assertEquals("Initial commit", commit.getMessage());
        assertNotNull(commit.getHash());
        assertNotNull(commit.getShortHash());
    }

    @Test
    void shouldExtractMultipleCommits() throws GitAPIException, IOException {
        // Given: 创建多个提交
        createCommit("Alice", "alice@example.com", "First commit", "file1.txt", "content1");
        createCommit("Bob", "bob@example.com", "Second commit", "file2.txt", "content2");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 验证提交数量
        assertEquals(2, commits.size());
        assertEquals("Bob", commits.get(0).getAuthor()); // 最新的在前
        assertEquals("Alice", commits.get(1).getAuthor());
    }

    @Test
    void shouldCalculateLinesChanged() throws GitAPIException, IOException {
        // Given: 创建一个有变动的提交
        createCommit("Alice", "alice@example.com", "Add file", "test.txt",
                "line1\nline2\nline3\n");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 验证行数统计
        CommitInfo commit = commits.get(0);
        assertEquals(3, commit.getLinesAdded());
        assertEquals(0, commit.getLinesDeleted());
        assertEquals(3, commit.getTotalLines());
    }

    @Test
    void shouldDetectFilesChanged() throws GitAPIException, IOException {
        // Given: 创建修改多个文件的提交
        createCommit("Alice", "alice@example.com", "Multi file commit",
                "file1.txt", "content1");
        createCommit("Alice", "alice@example.com", "Multi file commit",
                "file2.txt", "content2");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 验证文件变动统计
        CommitInfo commit = commits.get(0);
        assertEquals(1, commit.getFilesChanged());
        assertTrue(commit.getChangedFiles().contains("file2.txt"));
    }

    @Test
    void shouldExtractModulesFromChangedFiles() throws GitAPIException, IOException {
        // Given: 创建涉及不同模块的提交
        createCommit("Alice", "alice@example.com", "Add order service",
                "src/main/java/com/example/order/OrderService.java", "package com.example.order;");
        createCommit("Alice", "alice@example.com", "Add user service",
                "src/main/java/com/example/user/UserService.java", "package com.example.user;");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 验证模块检测
        CommitInfo secondCommit = commits.get(0);
        assertTrue(secondCommit.getModules().contains("user"));
        assertFalse(secondCommit.getModules().contains("order"));

        CommitInfo firstCommit = commits.get(1);
        assertTrue(firstCommit.getModules().contains("order"));
        assertFalse(firstCommit.getModules().contains("user"));
    }

    @Test
    void shouldExtractDateInCorrectFormat() throws GitAPIException, IOException {
        // Given: 创建提交
        createCommit("Alice", "alice@example.com", "Test commit", "test.txt", "test");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 验证日期格式
        CommitInfo commit = commits.get(0);
        assertNotNull(commit.getDate());
        // 验证可以解析为 ISO 格式
        assertDoesNotThrow(() -> LocalDateTime.parse(commit.getDate()));
    }

    @Test
    void shouldHandleEmptyRepository() throws GitAPIException {
        // Given: 空仓库（没有提交）
        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 返回空列表
        assertNotNull(commits);
        assertTrue(commits.isEmpty());
    }

    @Test
    void shouldHandleInitialCommitWithoutParent() throws GitAPIException, IOException {
        // Given: 首次提交（没有父提交）
        createCommit("Alice", "alice@example.com", "Initial commit", "README.md", "# Hello");

        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), Constants.HEAD);

        // Then: 应该正常提取，不应该抛出异常
        assertEquals(1, commits.size());
        assertEquals("Initial commit", commits.get(0).getMessage());
    }

    @Test
    void shouldReturnEmptyListForInvalidRef() throws GitAPIException {
        // Given: 不存在的引用
        // When: 提取元数据
        List<CommitInfo> commits = extractor.extract(git.getRepository(), "invalid-ref");

        // Then: 返回空列表而不是抛出异常
        assertNotNull(commits);
        assertTrue(commits.isEmpty());
    }

    // ========== 辅助方法 ==========

    private void createCommit(String author, String email, String message,
                              String fileName, String content) throws GitAPIException, IOException {
        Path file = tempDir.resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);

        git.add().addFilepattern(fileName).call();

        git.commit()
                .setAuthor(author, email)
                .setMessage(message)
                .call();
    }
}
