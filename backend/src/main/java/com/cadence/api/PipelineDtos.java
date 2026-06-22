package com.cadence.api;

import com.cadence.domain.SlaState;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * F51 Pipeline View wire DTOs (contracts/pipeline-api.md). Value-free EXCEPT {@code PipelineRow.name}/{@code .stage}
 * (the minimum-necessary identifiers on this authenticated internal screen — decrypted for authorized staff only,
 * never logged; FR-024). Bulk results carry only ids + a single coarse reason (no GDPR oracle; FR-018).
 */
public final class PipelineDtos {

    private PipelineDtos() {}

    // ----- list -----

    /** A composed pipeline row. {@code name}/{@code stage} are decrypted PII-adjacent (authorized roles only). */
    public record PipelineRow(String candidateId, String name, String stage, SlaState slaState,
                              PipelineSchedulingStatus schedulingStatus, String requisitionId,
                              String requisitionTitle, Instant lastActivityAt) {}

    /**
     * One page of the pipeline. {@code totalInScope} = active candidates in scope (pre-filter, incl. terminal);
     * {@code filteredCount} = rows after filters but before pagination (the honest pager total — a UI computing page
     * count MUST use {@code filteredCount}, not {@code totalInScope}, since the default view excludes terminal rows).
     * {@code truncated} is set when the in-scope active count exceeds {@code scanCap} (D4).
     */
    public record PipelinePage(List<PipelineRow> rows, int page, int size, long totalInScope, long filteredCount,
                               boolean truncated) {}

    /** Derived (computed, not stored) scheduling progress for a candidate (FR-005 mapping, single source of truth). */
    public enum PipelineSchedulingStatus {
        NO_LINK_SENT, LINK_SENT, SLOT_PICKED, CONFIRMED, NO_SHOW, RESCHEDULED, CANCELLED, EXPIRED
    }

    /** Default view excludes terminal/closed; INCLUDE_CLOSED reveals them (FR-003). */
    public enum PipelineStatusFilter {
        ACTIVE, INCLUDE_CLOSED;

        static PipelineStatusFilter parse(String raw) {
            if (raw == null || raw.isBlank()) return ACTIVE;
            try { return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new PipelineExceptions.InvalidRequestException(); }
        }
    }

    /** Sort key. Computed-field sorts (STAGE/SLA/SCHEDULING) are applied in memory; RECENT is the default. */
    public enum PipelineSort {
        STAGE, SLA, SCHEDULING, RECENT;

        static PipelineSort parse(String raw) {
            if (raw == null || raw.isBlank()) return RECENT;
            try { return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new PipelineExceptions.InvalidRequestException(); }
        }
    }

    // ----- bulk -----

    public enum BulkAction { SEND_SCHEDULING_LINK, SEND_UPDATE_EMAIL }

    /** Bulk request body. {@code templateId}/{@code rangeStart}/{@code rangeEnd}/{@code locationText} are for the
     *  scheduling-link verb; {@code messageType} (a permitted holding/update type) for the update verb. */
    public record BulkRequest(BulkAction action, List<String> candidateIds, String templateId, String locationText,
                              LocalDate rangeStart, LocalDate rangeEnd, String messageType) {}

    /** Per-candidate bulk outcome. {@code reason} is a single coarse value (e.g. {@code "not_contactable"}) only on
     *  SKIPPED — never the specific GDPR/consent cause (FR-018). */
    public record BulkResult(String candidateId, String outcome, String reason) {}

    public record BulkResponse(List<BulkResult> results) {}

    // ----- timeline -----

    public record TimelineEvent(Instant occurredAt, String type, String label) {}

    public record TimelineResponse(String candidateId, List<TimelineEvent> events, boolean feedbackPending) {}

    // ----- requisition management -----

    public record RequisitionDto(String id, String title, String status, String externalLabel, Instant createdAt) {}

    public record CreateRequisitionRequest(String title, String externalLabel) {}

    public record UpdateRequisitionRequest(String title, String status) {}

    public record AssignRequest(String memberId) {}

    /** Set or clear ({@code requisitionId == null}) a candidate's requisition link. */
    public record LinkRequest(String requisitionId) {}
}
