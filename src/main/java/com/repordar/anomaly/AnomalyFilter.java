package com.repordar.anomaly;

import com.repordar.config.AppProperties;
import com.repordar.git.CommitInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Anomaly detection filter orchestrator.
 * <p>
 * Orchestrates three anomaly rule engines: giant commits, volatile files, cross-domain commits.
 * Reads threshold configuration from AppProperties.
 *
 * @author frank
 */
@Slf4j
@Component
public class AnomalyFilter {

    private final GiantCommitRule giantCommitRule;
    private final VolatileFileRule volatileFileRule;
    private final CrossDomainRule crossDomainRule;

    public AnomalyFilter(AppProperties appProperties) {
        AppProperties.Anomaly anomaly = appProperties.getAnomaly();

        this.giantCommitRule = new GiantCommitRule(anomaly.getGiantCommitThreshold());
        this.volatileFileRule = new VolatileFileRule(anomaly.getVolatileWindowDays(), anomaly.getVolatileThreshold());
        this.crossDomainRule = new CrossDomainRule(anomaly.getCrossDomainThreshold());

        log.info("AnomalyFilter initialized with thresholds: " +
                        "giant={}, volatile={}days/{}, crossDomain={}",
                anomaly.getGiantCommitThreshold(),
                anomaly.getVolatileWindowDays(),
                anomaly.getVolatileThreshold(),
                anomaly.getCrossDomainThreshold());
    }

    /**
     * Detect all anomalies in commit list.
     *
     * @param commits commit list
     * @return anomaly detection result
     */
    public AnomalyResult detect(List<CommitInfo> commits) {
        List<CommitInfo> giantCommits = giantCommitRule.check(commits);
        List<VolatileFileRule.VolatileFile> volatileFiles = volatileFileRule.check(commits);
        List<CommitInfo> crossDomainCommits = crossDomainRule.check(commits);

        log.info("Anomaly detection completed: {} giant, {} volatile, {} cross-domain",
                giantCommits.size(), volatileFiles.size(), crossDomainCommits.size());

        return new AnomalyResult(giantCommits, volatileFiles, crossDomainCommits);
    }

    /**
     * Anomaly detection result data object.
     */
    @Data
    public static class AnomalyResult {
        private final List<CommitInfo> giantCommits;
        private final List<VolatileFileRule.VolatileFile> volatileFiles;
        private final List<CommitInfo> crossDomainCommits;

        public AnomalyResult(List<CommitInfo> giantCommits,
                            List<VolatileFileRule.VolatileFile> volatileFiles,
                            List<CommitInfo> crossDomainCommits) {
            this.giantCommits = giantCommits != null ? giantCommits : Collections.emptyList();
            this.volatileFiles = volatileFiles != null ? volatileFiles : Collections.emptyList();
            this.crossDomainCommits = crossDomainCommits != null ? crossDomainCommits : Collections.emptyList();
        }

        public static AnomalyResult empty() {
            return new AnomalyResult(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }
}
