package com.repordar.anomaly;

import com.repordar.config.AppProperties;
import com.repordar.git.CommitInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnomalyFilter unit test.
 *
 * @author frank
 */
class AnomalyFilterTest {

    private AnomalyFilter filter;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        AppProperties.Anomaly anomaly = new AppProperties.Anomaly();
        anomaly.setGiantCommitThreshold(100);
        anomaly.setVolatileWindowDays(7);
        anomaly.setVolatileThreshold(5);
        anomaly.setCrossDomainThreshold(3);
        props.setAnomaly(anomaly);

        filter = new AnomalyFilter(props);
    }

    @Test
    void shouldDetectGiantCommits() {
        // Given: commits with varying line changes
        CommitInfo smallCommit = createCommit("hash1", 50, 1);
        CommitInfo giantCommit = createCommit("hash2", 150, 1);

        // When: detect anomalies
        AnomalyFilter.AnomalyResult result = filter.detect(List.of(smallCommit, giantCommit));

        // Then: should detect giant commit
        assertEquals(1, result.getGiantCommits().size());
        assertEquals("hash2", result.getGiantCommits().get(0).getHash());
    }

    @Test
    void shouldReturnEmptyResultForEmptyCommitList() {
        // Given: empty commit list
        // When: detect anomalies
        AnomalyFilter.AnomalyResult result = filter.detect(List.of());

        // Then: should return empty result
        assertNotNull(result);
        assertTrue(result.getGiantCommits().isEmpty());
        assertTrue(result.getVolatileFiles().isEmpty());
        assertTrue(result.getCrossDomainCommits().isEmpty());
    }

    @Test
    void shouldDetectCrossDomainCommits() {
        // Given: commit affecting multiple modules
        CommitInfo crossDomainCommit = createCommitWithModules("hash1",
                java.util.Set.of("order", "user", "payment", "inventory"));

        // When: detect anomalies
        AnomalyFilter.AnomalyResult result = filter.detect(List.of(crossDomainCommit));

        // Then: should detect cross-domain commit
        assertEquals(1, result.getCrossDomainCommits().size());
        assertEquals("hash1", result.getCrossDomainCommits().get(0).getHash());
    }

    // Helper methods

    private CommitInfo createCommit(String hash, int totalLines, int filesChanged) {
        CommitInfo commit = new CommitInfo();
        commit.setHash(hash);
        commit.setShortHash(hash.length() >= 7 ? hash.substring(0, 7) : hash);
        commit.setAuthor("test");
        commit.setAuthorEmail("test@example.com");
        commit.setMessage("Test commit");
        commit.setDate("2024-01-01T00:00:00");
        commit.setTotalLines(totalLines);
        commit.setFilesChanged(filesChanged);
        commit.setLinesAdded(totalLines);
        commit.setLinesDeleted(0);
        commit.setChangedFiles(List.of("file.txt"));
        commit.setModules(Set.of("test"));
        return commit;
    }

    private CommitInfo createCommitWithModules(String hash, Set<String> modules) {
        CommitInfo commit = createCommit(hash, 10, 1);
        commit.setModules(modules);
        return commit;
    }
}
