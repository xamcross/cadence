package com.cadence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * F51 Pipeline View global knobs ({@code cadence.pipeline.*}). The list compose is bounded by {@code scanCap}
 * (the in-memory materialise-then-sort/filter bound; beyond it the response sets {@code truncated=true} -- no silent
 * cap, research D4). Bulk selections are capped by {@code bulkMax} (FR-020). {@code pageSize} is the default page.
 */
@Component
@ConfigurationProperties(prefix = "cadence.pipeline")
public class PipelineProperties {

    /** Max candidates materialised in one pipeline compose; beyond it the response is {@code truncated=true}. */
    private int scanCap = 1000;

    /** Max candidate ids in one bulk action (FR-020); over-limit -> 400 before any candidate is touched. */
    private int bulkMax = 100;

    /** Default page size for the pipeline list. */
    private int pageSize = 50;

    public int getScanCap() { return scanCap; }
    public void setScanCap(int scanCap) { this.scanCap = scanCap; }

    public int getBulkMax() { return bulkMax; }
    public void setBulkMax(int bulkMax) { this.bulkMax = bulkMax; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
