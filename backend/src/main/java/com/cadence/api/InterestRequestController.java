package com.cadence.api;

import com.cadence.api.InterestDtos.InterestRequestItem;
import com.cadence.api.InterestDtos.InterestRequestListResponse;
import com.cadence.api.InterestDtos.InviteRequest;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.InterestRequest;
import com.cadence.service.AuthAuditService;
import com.cadence.service.InterestRequestService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * F70 Admin review queue (contracts/interest-api.md) — {@code /api/internal/interest-requests}. Class-level
 * {@code @PreAuthorize("hasRole('ADMIN')")} is the single role source of truth (satisfies
 * RbacEndpointInventoryTest). Workspace + actor are read from the session principal (never submitter input). A
 * cross-workspace / absent id -> ScopedNotFoundException -> indistinguishable 404 via
 * {@link InterestExceptionHandler}. List is {@code no-store} (carries decrypted submitter PII).
 */
@RestController
@RequestMapping("/api/internal/interest-requests")
@PreAuthorize("hasRole('ADMIN')")
public class InterestRequestController {

    private final InterestRequestService service;
    private final AuthAuditService audit;

    public InterestRequestController(InterestRequestService service, AuthAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    public ResponseEntity<InterestRequestListResponse> list(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(name = "status", required = false) String status) {
        List<InterestRequestItem> items = service.list(principal.workspaceId(), status).stream()
            .map(InterestRequestController::toItem)
            .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new InterestRequestListResponse(items));
    }

    /**
     * CSV export of the review queue (the DashboardController export precedent). Admin-only via the class-level
     * {@code @PreAuthorize}, workspace-scoped from the principal, same status-filter semantics as the list. The CSV
     * is a deliberate PII egress -> record one attributable audit event (status filter + row count, NO submitter
     * names; the DASHBOARD_EXPORTED precedent). Every free-text cell is neutralized at the export boundary by the
     * service via {@code CsvInjectionEscaper} (SC-012/FR-010). {@code no-store}; sourceIp is not needed (pass null).
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(name = "status", required = false) String status) {
        InterestRequestService.ExportResult result =
            service.exportCsv(principal.workspaceId(), status, principal.memberId());
        String filter = status == null || status.isBlank() ? "open" : status.trim().toLowerCase();
        audit.record(AuthEventType.INTEREST_REQUESTS_EXPORTED, principal.workspaceId(), principal.memberId(),
            "status=" + filter + ";rows=" + result.rowCount(), null);
        byte[] body = result.csv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"interest-requests.csv\"")
            .body(body);
    }

    @PostMapping("/{id}/review")
    public ResponseEntity<Map<String, Object>> review(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        service.review(principal.workspaceId(), id, principal.memberId());
        return ResponseEntity.ok(Map.of("status", "REVIEWED"));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        service.dismiss(principal.workspaceId(), id, principal.memberId());
        return ResponseEntity.ok(Map.of("status", "DISMISSED"));
    }

    @PostMapping("/{id}/invite")
    public ResponseEntity<Map<String, Object>> invite(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id,
            @RequestBody(required = false) InviteRequest req,
            HttpServletRequest http) {
        if (req == null || req.role() == null) {
            throw new InterestExceptions.InvalidRequestException();
        }
        InterestRequestService.InviteResult result = service.invite(
            principal.workspaceId(), id, req.role(), principal.memberId(), http.getRemoteAddr());
        if (result.alreadyMember()) {
            return ResponseEntity.ok(Map.of("status", "INVITED", "alreadyMember", true));
        }
        return ResponseEntity.ok(Map.of("status", "INVITED", "invitationId", result.invitationId()));
    }

    @PostMapping("/{id}/erase")
    public ResponseEntity<Map<String, Object>> erase(
            @AuthenticationPrincipal SessionService.Principal principal, @PathVariable String id) {
        service.erase(principal.workspaceId(), id);
        return ResponseEntity.ok(Map.of("status", "erased"));
    }

    /** Submitter-claimed data is labelled "unverified" (constant flags) — Assumptions / US2 Sc.1. */
    private static InterestRequestItem toItem(InterestRequest r) {
        return new InterestRequestItem(r.getId(), r.getName(), r.getEmail(), true,
            r.getOrganization(), true, r.getMessage(), r.getStatus(), r.getSubmittedAt());
    }
}
