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
            return new StatusResponse(v.status(), v.sentAt(), v.expiresAt(), v.chosenStart());
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

    // --- F20 candidate booking management (public-by-manage-token) ---

    /** Current booking + capabilities — times only, never participant identity or location (FR-020). */
    public record BookingResponse(String status, Instant bookedStart, String zoneId, Instant at,
                                  boolean canReschedule, boolean canCancel, int rescheduleRemaining) {
        public static BookingResponse from(SlotReservationService.BookingView v) {
            return new BookingResponse(v.status(), v.bookedStart(), v.zoneId(), v.at(),
                v.canReschedule(), v.canCancel(), v.rescheduleRemaining());
        }
    }

    /** The reschedule round's slot-pick token (TLS body only) + the offered times. */
    public record OpenRescheduleResponse(String rescheduleToken, String zoneHint, List<SlotView> slots) {
        public static OpenRescheduleResponse from(SlotReservationService.OpenRescheduleResult r) {
            List<SlotView> slots = r.slots().stream()
                .map(s -> new SlotView(s.slotId(), s.start(), s.end(), s.zoneId())).toList();
            return new OpenRescheduleResponse(r.rescheduleToken(), r.zoneHint(), slots);
        }
    }

    public record CancelBookingResponse(String status, Instant at) {
        public static CancelBookingResponse from(SlotReservationService.CancelResult r) {
            return new CancelBookingResponse(r.cleanupIncomplete() ? "cleanup_incomplete" : "cancelled", r.at());
        }
    }

    // --- F20 recruiter reschedule/cancel (internal) ---

    public record RecruiterRescheduleResponse(String status, Instant invitedAt) {
        public static RecruiterRescheduleResponse from(SchedulingService.RescheduleInviteResult r) {
            return new RecruiterRescheduleResponse("reschedule_in_progress", r.invitedAt());
        }
    }

    public record RecruiterCancelResponse(String status, Instant at) {
        public static RecruiterCancelResponse from(SchedulingService.CancelOutcome r) {
            return new RecruiterCancelResponse(r.cleanupIncomplete() ? "cleanup_incomplete" : "cancelled", r.at());
        }
    }
}
