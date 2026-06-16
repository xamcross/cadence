package com.cadence.api;

import com.cadence.api.SchedulingDtos.CandidateSlotsResponse;
import com.cadence.api.SchedulingDtos.ConfirmRequest;
import com.cadence.api.SchedulingDtos.ConfirmResponse;
import com.cadence.service.SlotReservationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F13 candidate self-scheduling (contract B) — {@code /api/candidate/scheduling/{token}}. Public-by-token on
 * the existing {@code @Order(2)} permitAll/STATELESS chain (no session, no {@code @PreAuthorize}; the token IS
 * the auth — the {@code /api/candidate/} prefix is allow-listed in RbacEndpointInventoryTest). Rate-limited
 * per IP (429). The slots payload carries times only — never participant identity (FR-011); no-store.
 */
@RestController
@RequestMapping("/api/candidate/scheduling")
public class CandidateSchedulingController {

    private final SlotReservationService service;

    public CandidateSchedulingController(SlotReservationService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public ResponseEntity<CandidateSlotsResponse> view(@PathVariable String token, HttpServletRequest http) {
        SlotReservationService.ViewResult result = service.view(token, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(CandidateSlotsResponse.from(result));
    }

    @PostMapping("/{token}/confirm")
    public ResponseEntity<ConfirmResponse> confirm(
            @PathVariable String token,
            @RequestBody(required = false) ConfirmRequest req,
            HttpServletRequest http) {
        if (req == null || req.slotId() == null || req.slotId().isBlank()) {
            throw new SchedulingExceptions.InvalidRequestException("slotId is required.");
        }
        SlotReservationService.ConfirmResult result = service.confirm(token, req.slotId(), http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ConfirmResponse.from(result));
    }
}
