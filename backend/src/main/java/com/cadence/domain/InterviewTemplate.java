package com.cadence.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, workspace-scoped definition of an interview type (F12, data-model §1). The source of
 * the rules the {@code RuleEngine} applies. Holds ONLY internal member-id references and instants —
 * NO candidate/participant PII and NO secret — so it needs no encryption converter (asserted by a
 * raw-driver test, mirroring {@link ManagedCalendarEvent}).
 *
 * <p>The recruiter-supplied {@code name} is free text that may contain PII; it is returned on the
 * management read model but is NEVER logged or audited (FR-022/FR-023) — {@link #toString()} omits it.
 */
@Document(collection = "interviewTemplates")
public class InterviewTemplate {

    @Id
    private String id;

    private String workspaceId;
    /** Recruiter free text — possible PII vector. Never logged/audited; omitted from toString(). */
    private String name;
    private TemplateStatus status = TemplateStatus.ACTIVE;

    private int durationMinutes;
    private int slotCadenceMinutes;
    private int bufferBeforeMinutes;
    private int bufferAfterMinutes;
    private int dailyCapPerInterviewer;

    private List<String> requiredMemberIds = new ArrayList<>();
    private List<String> optionalMemberIds = new ArrayList<>();
    private List<PoolRule> pools = new ArrayList<>();
    private List<BlackoutPeriod> blackouts = new ArrayList<>();

    /** null => inherit the workspace time zone (F03) at compute time (FR-018). */
    private String timeZoneOverride;
    /** null => inherit the workspace working hours (F03) at compute time (FR-018). */
    private WorkingHours workingHoursOverride;

    private String createdByMemberId;
    private Instant createdAt;
    private Instant updatedAt;

    public InterviewTemplate() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TemplateStatus getStatus() { return status; }
    public void setStatus(TemplateStatus status) { this.status = status; }

    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }

    public int getSlotCadenceMinutes() { return slotCadenceMinutes; }
    public void setSlotCadenceMinutes(int slotCadenceMinutes) { this.slotCadenceMinutes = slotCadenceMinutes; }

    public int getBufferBeforeMinutes() { return bufferBeforeMinutes; }
    public void setBufferBeforeMinutes(int bufferBeforeMinutes) { this.bufferBeforeMinutes = bufferBeforeMinutes; }

    public int getBufferAfterMinutes() { return bufferAfterMinutes; }
    public void setBufferAfterMinutes(int bufferAfterMinutes) { this.bufferAfterMinutes = bufferAfterMinutes; }

    public int getDailyCapPerInterviewer() { return dailyCapPerInterviewer; }
    public void setDailyCapPerInterviewer(int dailyCapPerInterviewer) { this.dailyCapPerInterviewer = dailyCapPerInterviewer; }

    public List<String> getRequiredMemberIds() { return requiredMemberIds; }
    public void setRequiredMemberIds(List<String> requiredMemberIds) { this.requiredMemberIds = requiredMemberIds; }

    public List<String> getOptionalMemberIds() { return optionalMemberIds; }
    public void setOptionalMemberIds(List<String> optionalMemberIds) { this.optionalMemberIds = optionalMemberIds; }

    public List<PoolRule> getPools() { return pools; }
    public void setPools(List<PoolRule> pools) { this.pools = pools; }

    public List<BlackoutPeriod> getBlackouts() { return blackouts; }
    public void setBlackouts(List<BlackoutPeriod> blackouts) { this.blackouts = blackouts; }

    public String getTimeZoneOverride() { return timeZoneOverride; }
    public void setTimeZoneOverride(String timeZoneOverride) { this.timeZoneOverride = timeZoneOverride; }

    public WorkingHours getWorkingHoursOverride() { return workingHoursOverride; }
    public void setWorkingHoursOverride(WorkingHours workingHoursOverride) { this.workingHoursOverride = workingHoursOverride; }

    public String getCreatedByMemberId() { return createdByMemberId; }
    public void setCreatedByMemberId(String createdByMemberId) { this.createdByMemberId = createdByMemberId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public boolean isActive() { return status == TemplateStatus.ACTIVE; }

    /** Deliberately OMITS name (the PII vector — D10). Member ids are internal, not PII. */
    @Override
    public String toString() {
        return "InterviewTemplate{id=" + id + ", workspaceId=" + workspaceId + ", status=" + status
            + ", durationMinutes=" + durationMinutes + ", required=" + requiredMemberIds.size()
            + ", pools=" + pools.size() + "}";
    }
}
