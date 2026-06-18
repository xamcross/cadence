package com.cadence.domain;

import com.cadence.integration.AtsProvider;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One record per inbound ATS sync run (F40, data-model section 3). Drives the "last successful sync"
 * status surface + the degraded indicator; bounded newest-first read by workspace.
 *
 * <p><b>No PII at rest</b>: ids / counts / instants / value-free outcome + error category only.
 * Un-encrypted by design, like {@code emailDispatches}/{@code atsWriteBacks}.
 */
@Document(collection = "atsSyncRuns")
public class AtsSyncRun {

    @Id
    private String id;

    private String workspaceId;

    /** The provider this sync run polled (F41) — enables per-provider "last successful sync" (SC-011). */
    @Field(value = "provider", write = Field.Write.NON_NULL)
    private AtsProvider provider;

    private Instant startedAt;
    private Instant finishedAt;

    /** {@code SUCCESS} / {@code PARTIAL} / {@code FAILED}. */
    private String outcome;

    private int processed;
    private int created;
    private int updated;
    private int skipped;

    /** Value-free category only (nullable). */
    @Field(value = "errorCategory", write = Field.Write.NON_NULL)
    private String errorCategory;

    public AtsSyncRun() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public AtsProvider getProvider() { return provider; }
    public void setProvider(AtsProvider provider) { this.provider = provider; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public int getProcessed() { return processed; }
    public void setProcessed(int processed) { this.processed = processed; }

    public int getCreated() { return created; }
    public void setCreated(int created) { this.created = created; }

    public int getUpdated() { return updated; }
    public void setUpdated(int updated) { this.updated = updated; }

    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }

    public String getErrorCategory() { return errorCategory; }
    public void setErrorCategory(String errorCategory) { this.errorCategory = errorCategory; }

    /** Ids + counts + instants only - no PII. */
    @Override
    public String toString() {
        return "AtsSyncRun{id=" + id + ", workspaceId=" + workspaceId + ", provider=" + provider + ", startedAt=" + startedAt
            + ", finishedAt=" + finishedAt + ", outcome=" + outcome + ", processed=" + processed
            + ", created=" + created + ", updated=" + updated + ", skipped=" + skipped
            + ", errorCategory=" + errorCategory + "}";
    }
}
