package com.cadence.api;

import com.cadence.domain.BlackoutPeriod;
import com.cadence.domain.ComputedSlot;
import com.cadence.domain.InterviewTemplate;
import com.cadence.domain.MemberUnschedulable;
import com.cadence.domain.PoolRule;
import com.cadence.domain.SlotComputationResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** F12 request/response DTOs (contract §A/§B/§C). The response carries member ids only — never email/name. */
public final class InterviewTemplateDtos {

    private InterviewTemplateDtos() {}

    public record WorkingHoursDto(LocalTime start, LocalTime end) {}

    public record PoolRuleDto(List<String> memberIds, Integer n) {}

    public record BlackoutDto(Instant start, Instant end) {}

    /** Create/edit request (same shape for PUT). {@code slotCadenceMinutes} null => the configured default. */
    public record TemplateRequest(
        String name,
        Integer durationMinutes,
        Integer slotCadenceMinutes,
        Integer bufferBeforeMinutes,
        Integer bufferAfterMinutes,
        Integer dailyCapPerInterviewer,
        List<String> requiredMemberIds,
        List<String> optionalMemberIds,
        List<PoolRuleDto> pools,
        List<BlackoutDto> blackouts,
        String timeZoneOverride,
        WorkingHoursDto workingHoursOverride) {}

    public record TemplateResponse(
        String id,
        String workspaceId,
        String name,
        String status,
        int durationMinutes,
        int slotCadenceMinutes,
        int bufferBeforeMinutes,
        int bufferAfterMinutes,
        int dailyCapPerInterviewer,
        List<String> requiredMemberIds,
        List<String> optionalMemberIds,
        List<PoolRuleDto> pools,
        List<BlackoutDto> blackouts,
        String timeZoneOverride,
        WorkingHoursDto workingHoursOverride,
        String createdByMemberId,
        Instant createdAt,
        Instant updatedAt) {

        public static TemplateResponse from(InterviewTemplate t) {
            List<PoolRuleDto> pools = t.getPools().stream()
                .map(p -> new PoolRuleDto(p.getMemberIds(), p.getN())).toList();
            List<BlackoutDto> blackouts = t.getBlackouts().stream()
                .map(b -> new BlackoutDto(b.getStart(), b.getEnd())).toList();
            WorkingHoursDto wh = t.getWorkingHoursOverride() == null ? null
                : new WorkingHoursDto(t.getWorkingHoursOverride().getStart(), t.getWorkingHoursOverride().getEnd());
            return new TemplateResponse(
                t.getId(), t.getWorkspaceId(), t.getName(), t.getStatus().name(),
                t.getDurationMinutes(), t.getSlotCadenceMinutes(),
                t.getBufferBeforeMinutes(), t.getBufferAfterMinutes(), t.getDailyCapPerInterviewer(),
                t.getRequiredMemberIds(), t.getOptionalMemberIds(), pools, blackouts,
                t.getTimeZoneOverride(), wh, t.getCreatedByMemberId(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record ListResponse(List<TemplateResponse> templates) {}

    public record SlotPreviewRequest(LocalDate rangeStart, LocalDate rangeEnd) {}

    /** One slot in the response — the per-pool qualifying map keyed by pool index (FR-010). */
    public record SlotResponse(Instant start, Instant end, String zoneId,
                               List<String> requiredMemberIds,
                               Map<Integer, List<String>> qualifyingByPool) {
        static SlotResponse from(ComputedSlot s) {
            return new SlotResponse(s.start(), s.end(), s.zoneId(), s.requiredMemberIds(), s.qualifyingByPoolIndex());
        }
    }

    public record SlotComputationResponse(List<SlotResponse> slots, boolean windowClamped,
                                          List<MemberUnschedulable> unschedulable) {
        public static SlotComputationResponse from(SlotComputationResult r) {
            return new SlotComputationResponse(
                r.slots().stream().map(SlotResponse::from).toList(), r.windowClamped(), r.unschedulable());
        }
    }
}
