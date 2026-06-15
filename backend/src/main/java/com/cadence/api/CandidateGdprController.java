package com.cadence.api;

import com.cadence.domain.CandidateAuditEvent;
import com.cadence.domain.CandidateAuditOutcome;
import com.cadence.domain.LawfulBasis;
import com.cadence.service.CandidateAuditService;
import com.cadence.service.CandidateErasureService;
import com.cadence.service.CandidateService;
import com.cadence.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator GDPR surface for a candidate (F04). Erasure + record/withdraw basis are Admin OR Recruiter;
 * the audit read is Admin only. All under the internal prefix so RbacEndpointInventoryTest enforces a
 * role on every handler. F04 ships NO candidate-create endpoint (creation is CandidateService.create).
 */
@RestController
@RequestMapping("/api/internal/candidates")
public class CandidateGdprController {

    private final CandidateService candidateService;
    private final CandidateErasureService erasureService;
    private final CandidateAuditService auditService;

    public CandidateGdprController(CandidateService candidateService, CandidateErasureService erasureService,
                                   CandidateAuditService auditService) {
        this.candidateService = candidateService;
        this.erasureService = erasureService;
        this.auditService = auditService;
    }

    /** US2: operator-triggered erasure. Returns an identical 200 for all ids (no existence oracle). */
    @PostMapping("/{id}/erasure")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public ResponseEntity<GdprDtos.StatusResponse> erase(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        erasureService.wipe(principal.workspaceId(), id, CandidateAuditOutcome.OPERATOR, principal.memberId());
        return ResponseEntity.ok(new GdprDtos.StatusResponse("erased"));
    }

    /** US1: record (or re-record) the email lawful basis. */
    @PutMapping("/{id}/basis")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public ResponseEntity<GdprDtos.BasisRecordedResponse> recordBasis(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id,
            @RequestBody GdprDtos.BasisRequest req) {
        candidateService.recordBasis(principal.workspaceId(), id, parseBasis(req.lawfulBasis()), principal.memberId());
        return ResponseEntity.ok(new GdprDtos.BasisRecordedResponse(true));
    }

    /** US1: withdraw the lawful basis (opt-out). */
    @DeleteMapping("/{id}/basis")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public ResponseEntity<GdprDtos.BasisWithdrawnResponse> withdrawBasis(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        candidateService.withdrawBasis(principal.workspaceId(), id, principal.memberId());
        return ResponseEntity.ok(new GdprDtos.BasisWithdrawnResponse(true));
    }

    /** US3: read the candidate's append-only audit log (non-PII, ordered). Admin only. */
    @GetMapping("/{id}/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GdprDtos.AuditLogResponse> audit(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        List<GdprDtos.AuditEntryResponse> entries = auditService.list(principal.workspaceId(), id).stream()
            .map(CandidateGdprController::view).toList();
        return ResponseEntity.ok(new GdprDtos.AuditLogResponse(entries));
    }

    private static GdprDtos.AuditEntryResponse view(CandidateAuditEvent e) {
        return new GdprDtos.AuditEntryResponse(
            e.getEventType().name(), e.getOutcome().name(), e.getActorMemberId(), e.getOccurredAt());
    }

    private static LawfulBasis parseBasis(String value) {
        if (value == null) {
            throw new GdprExceptions.InvalidBasisException();
        }
        try {
            return LawfulBasis.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new GdprExceptions.InvalidBasisException();
        }
    }
}
