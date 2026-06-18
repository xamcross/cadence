package com.cadence.api;

import com.cadence.api.DashboardDtos.DashboardSnapshot;
import com.cadence.domain.AuthEventType;
import com.cadence.service.AuthAuditService;
import com.cadence.service.DashboardService;
import com.cadence.service.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * F50 Core Dashboard internal surface (contracts/dashboard-api.md). Read is {@code ADMIN/RECRUITER/READ_ONLY}
 * (class-level); export is {@code ADMIN/RECRUITER} (method-level override -> Read-only denied export, FR-021).
 * Interviewer + Hiring Manager are denied by deny-by-default (HM deferred to F51, FR-026). Workspace-scoped from
 * the principal only -- a client-supplied workspace id is never trusted (FR-022). Both endpoints are
 * {@code no-store}. Covered by {@code RbacEndpointInventoryTest} via the class-level {@code @PreAuthorize}.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY')")
public class DashboardController {

    private final DashboardService service;
    private final AuthAuditService audit;

    public DashboardController(DashboardService service, AuthAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/api/internal/dashboard")
    public ResponseEntity<DashboardSnapshot> dashboard(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(name = "window", required = false) String window) {
        DashboardWindow w = DashboardWindow.parse(window);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.snapshot(principal.workspaceId(), w));
    }

    @GetMapping("/api/internal/dashboard/export")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(name = "window", required = false) String window) {
        DashboardWindow w = DashboardWindow.parse(window);
        DashboardSnapshot snap = service.snapshot(principal.workspaceId(), w);
        String csv = service.renderCsv(snap);
        // The CSV is a deliberate PII egress -> record one attributable audit event (window + row count, NO
        // names; FR-019b/SC-012). sourceIp is not needed (the audit is who/when/how-much); pass null (the F23
        // system-event precedent; AuthAuditService HMAC-hashes and is null-safe).
        audit.record(AuthEventType.DASHBOARD_EXPORTED, principal.workspaceId(), principal.memberId(),
            "window=" + w.name() + ";rows=" + snap.silenceList().size(), null);
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dashboard-" + w.name() + ".csv\"")
            .body(body);
    }
}
