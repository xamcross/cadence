package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The workspace logo bytes, kept in a SEPARATE collection from {@link WorkspaceConfig} (research D1)
 * so the small, hot config document is not bloated by the cold image blob and stays well under the
 * 16 MB BSON limit. One document per {@code workspaceId} (unique index, ChangeUnit004). The bytes are
 * a verified raster (PNG/JPEG) <= 1 MB; {@code contentType} is the magic-byte-verified type, not the
 * client-supplied one (research D6).
 */
@Document(collection = "workspaceLogo")
public class WorkspaceLogo {

    @Id
    private String id;

    private String workspaceId;
    private byte[] bytes;
    private String contentType;
    private int size;
    private Instant updatedAt;

    public WorkspaceLogo() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public byte[] getBytes() { return bytes; }
    public void setBytes(byte[] bytes) { this.bytes = bytes; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
