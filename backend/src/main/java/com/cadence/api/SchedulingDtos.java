package com.cadence.api;

import com.cadence.service.SchedulingService;
import com.cadence.service.SlotReservationService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** F13 wire records (contract A/B). Candidate-facing shapes carry times only — never participant identity. */
public final class SchedulingDtos {

    private SchedulingDtos() {}

    // --- Recruiter (internal) ---

    public record InitiateRequest(String templateId, String locationText, LocalDate rangeStart, LocalDate rangeEnd) {}

    public record InitiateResponse(String schedulingRequestId, String status, int offeredSlotCount,
                                   Instant sentAt, Instant expiresAt) {
        public static InitiateResponse from(SchedulingService.InitiateResult r) {
            return new InitiateResponse(r.schedulingRequestId(), r.status().name(), r.offeredSlotCount(),
                r.sentAt(), r.expiresAt());
        }
    }

    public record StatusResponse(String status, Instant sentAt, Instant expiresAt, Instant chosenStart) {
        public static StatusResponse from(SchedulingService.StatusView v) {
            return new StatusResponse(v.status().name(), v.sentAt(), v.expiresAt(), v.chosenStart());
        }
    }

    // --- Candidate (public-by-token) ---

    public record SlotView(String slotId, Instant start, Instant end, String zoneId) {}

    public record CandidateSlotsResponse(String status, String zoneHint, Instant bookedStart, List<SlotView> slots) {
        public static CandidateSlotsResponse from(SlotReservationService.ViewResult r) {
            if (r.booked()) {
                return new CandidateSlotsResponse("booked", r.zoneHint(), r.bookedStart(), List.of());
            }
            List<SlotView> slots = r.slots().stream()
                .map(s -> new SlotView(s.slotId(), s.start(), s.end(), s.zoneId())).toList();
            return new CandidateSlotsResponse("open", r.zoneHint(), null, slots);
        }
    }

    public record ConfirmRequest(String slotId) {}

    public record ConfirmResponse(String status, Instant bookedStart, String zoneId) {
        public static ConfirmResponse from(SlotReservationService.ConfirmResult r) {
            return new ConfirmResponse("booked", r.bookedStart(), r.zoneId());
        }
    }
}
