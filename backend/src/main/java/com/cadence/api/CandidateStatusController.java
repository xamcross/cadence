package com.cadence.api;

import com.cadence.api.CandidateStatusDtos.CandidateStatusView;
import com.cadence.api.CandidateStatusDtos.ErasureAckResponse;
import com.cadence.service.CandidateStatusService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F30 candidate-facing status page — {@code /api/candidate/status/{token}}. Public-by-token on the existing
 * {@code @Order(2)} permitAll/STATELESS chain (no session, no {@code @PreAuthorize}; the status token IS the
 * auth — the {@code /api/candidate/} prefix is allow-listed in RbacEndpointInventoryTest). Rate-limited per IP
 * (429). The candidate is resolved SOLELY from the credential (no IDOR). View is times+status-text only; the
 * erasure-submit is an affirmative POST (never a GET — no prefetch/scanner auto-submit, FR-023). {@code no-store}.
 *
 * <p>The byte-identical 404 (unknown/malformed/erased) and the 429 are mapped by
 * {@link CandidateStatusExceptionHandler} (the no-oracle piece, NOT inherited from SchedulingExceptionHandler).
 */
@RestController
@RequestMapping("/api/candidate/status")
public class CandidateStatusController {

    private final CandidateStatusService service;

    public CandidateStatusController(CandidateStatusService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    public ResponseEntity<CandidateStatusView> view(@PathVariable String token, HttpServletRequest http) {
        CandidateStatusView v = service.view(token, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);
    }

    /**
     * Candidate requests erasure (FR-018..023). Affirmative POST — a {@code GET} → 405 so a prefetch/scanner
     * cannot trigger it. ALWAYS the same 202 ack regardless of {valid, unknown, malformed, erased} (no oracle,
     * SC-010); a request is recorded only when the token resolves to an active candidate; idempotent (no 2nd PENDING).
     */
    @PostMapping("/{token}/erasure-request")
    public ResponseEntity<ErasureAckResponse> requestErasure(@PathVariable String token, HttpServletRequest http) {
        service.requestErasureByToken(token, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
            .body(ErasureAckResponse.received());
    }
}
