package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * F42 Standalone CSV Import configuration (prefix {@code cadence.import}). The async import worker, the
 * size/row/field bounds, the &gt;80% whole-file reject threshold, and the job TTL are all driven from here so
 * the test profile can shrink them for deterministic tests (the F23 past-stamp lesson).
 *
 * <p>Invariant (D8): {@code processing-threshold > sweep-fixed-delay + max per-job processing time} so the
 * orphan reaper never races a live worker. The container multipart caps sit ABOVE {@code max-file-size} (D9).
 *
 * <p>Auto-registers via the existing {@code @ConfigurationPropertiesScan} on {@code CadenceApplication}.
 */
@ConfigurationProperties(prefix = "cadence.import")
public class ImportProperties {

    /** In-service upload size gate (the multipart caps sit above this — D9). */
    private DataSize maxFileSize = DataSize.ofMegabytes(5);
    /** Row-count DoS bound (FR-004). */
    private int maxRowCount = 10000;
    /** Per-field length bound (FR-004 memory guard). */
    private int maxFieldLength = 4096;
    /** Whole-file reject when {@code failures / N > rejectRatio} (FR-008/D7). */
    private double rejectRatio = 0.80;
    /** Worker poll interval (read directly by {@code @Scheduled}; not used as a bound here). */
    private Duration sweepFixedDelay = Duration.ofSeconds(5);
    /** Jobs claimed per sweep (the F12 Pageable cap). */
    private int sweepBatchLimit = 20;
    /** Unresolved-duplicate / orphan expiry (FR-021a/D8). */
    private Duration jobTtl = Duration.ofHours(24);
    /** Orphan-PROCESSING reaper bound (must exceed sweep-fixed-delay + max per-job time). */
    private Duration processingThreshold = Duration.ofMinutes(15);

    public DataSize getMaxFileSize() { return maxFileSize; }
    public void setMaxFileSize(DataSize maxFileSize) { this.maxFileSize = maxFileSize; }

    public int getMaxRowCount() { return maxRowCount; }
    public void setMaxRowCount(int maxRowCount) { this.maxRowCount = maxRowCount; }

    public int getMaxFieldLength() { return maxFieldLength; }
    public void setMaxFieldLength(int maxFieldLength) { this.maxFieldLength = maxFieldLength; }

    public double getRejectRatio() { return rejectRatio; }
    public void setRejectRatio(double rejectRatio) { this.rejectRatio = rejectRatio; }

    public Duration getSweepFixedDelay() { return sweepFixedDelay; }
    public void setSweepFixedDelay(Duration sweepFixedDelay) { this.sweepFixedDelay = sweepFixedDelay; }

    public int getSweepBatchLimit() { return sweepBatchLimit; }
    public void setSweepBatchLimit(int sweepBatchLimit) { this.sweepBatchLimit = sweepBatchLimit; }

    public Duration getJobTtl() { return jobTtl; }
    public void setJobTtl(Duration jobTtl) { this.jobTtl = jobTtl; }

    public Duration getProcessingThreshold() { return processingThreshold; }
    public void setProcessingThreshold(Duration processingThreshold) { this.processingThreshold = processingThreshold; }
}
