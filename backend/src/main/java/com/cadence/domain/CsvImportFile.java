package com.cadence.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * F42 raw uploaded bytes, held only between {@code 202} and processing, then disposed on every terminal path /
 * TTL (FR-021/FR-021a). Stored in a separate collection (the {@code workspaceLogo} small-hot-doc precedent).
 *
 * <p>{@code dataBase64} is a base64 String precisely so the registered {@code PiiStringConverter} (which is
 * {@code String->String} and cannot encrypt a {@code byte[]}) encrypts it at rest — the
 * {@code emailProviderCredential}/{@code statusToken} encryption precedent. A raw-driver read shows ciphertext.
 */
@Document(collection = "csvImportFiles")
public class CsvImportFile {

    @Id
    private String id;

    private String jobId;
    private String workspaceId;

    /** base64 of the raw uploaded bytes — converter-encrypted at rest. Never serialized, never logged. */
    @JsonIgnore
    @Field(write = Field.Write.NON_NULL)
    private String dataBase64;

    private String contentType;
    private long sizeBytes;
    private Instant createdAt;

    public CsvImportFile() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getDataBase64() { return dataBase64; }
    public void setDataBase64(String dataBase64) { this.dataBase64 = dataBase64; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    /** Deliberately omits dataBase64 (raw PII). */
    @Override
    public String toString() {
        return "CsvImportFile{id=" + id + ", jobId=" + jobId + ", sizeBytes=" + sizeBytes + "}";
    }
}
