package com.cadence.api;

import com.cadence.api.SchedulingDtos.BookingResponse;
import com.cadence.api.SchedulingDtos.CancelBookingResponse;
import com.cadence.api.SchedulingDtos.OpenRescheduleResponse;
import com.cadence.service.SlotReservationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F20 candidate booking management (Flow A3) — {@code /api/candidate/booking/{token}}. Public-by-manage-token
 * on the existing {@code @Order(2)} permitAll/STATELESS chain (no session, no {@code @PreAuthorize}; the manage
 * token IS the auth — the {@code /api/candidate/} prefix is allow-listed in RbacEndpointInventoryTest).
 * Rate-limited per IP (429). The booking is resolved SOLELY from the credential — no client-supplied id may
 * override it (FR-017a, no IDOR); request bodies are ignored for target resolution. Times only — never
 * participant identity or location (FR-020); no-store. Cancel is an affirmative POST (never a GET — no
 * prefetch/scanner auto-cancel, FR-012).
 */
@RestController
@RequestMapping("/api/candidate/booking")
public class CandidateBookingController {

    private final SlotReservationService service;

    public CandidateBookingController(SlotReservationService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public ResponseEntity<BookingResponse> view(@PathVariable String token, HttpServletRequest http) {
        SlotReservationService.BookingView v = service.viewBooking(token, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BookingResponse.from(v));
    }

    @PostMapping("/{token}/reschedule")
    public ResponseEntity<OpenRescheduleResponse> reschedule(@PathVariable String token, HttpServletRequest http) {
        SlotReservationService.OpenRescheduleResult r = service.openReschedule(token, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OpenRescheduleResponse.from(r));
    }

    @PostMapping("/{token}/cancel")
    public ResponseEntity<CancelBookingResponse> cancel(@PathVariable String token, HttpServletRequest http) {
        SlotReservationService.CancelResult r = service.cancel(token, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CancelBookingResponse.from(r));
    }
}
