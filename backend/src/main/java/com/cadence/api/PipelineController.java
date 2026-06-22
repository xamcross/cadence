package com.cadence.api;

import com.cadence.api.PipelineDtos.BulkRequest;
import com.cadence.api.PipelineDtos.BulkResponse;
import com.cadence.api.PipelineDtos.PipelinePage;
import com.cadence.api.PipelineDtos.PipelineSchedulingStatus;
import com.cadence.api.PipelineDtos.PipelineSort;
import com.cadence.api.PipelineDtos.PipelineStatusFilter;
import com.cadence.api.PipelineDtos.TimelineResponse;
import com.cadence.domain.SlaState;
import com.cadence.service.PipelineBulkService;
import com.cadence.service.PipelineService;
import com.cadence.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * F51 Pipeline View internal surface (contracts/pipeline-api.md). List + timeline are
 * {@code ADMIN/RECRUITER/READ_ONLY/HIRING_MANAGER} (class-level; Interviewer denied by deny-by-default). Bulk is
 * {@code ADMIN/RECRUITER} (method-level override). Visibility is scoped server-side from the principal's persisted
 * role (HM -> assigned requisitions only); a client-supplied workspace id is never trusted (FR-012/FR-013). All
 * responses are {@code no-store}. Covered by {@code RbacEndpointInventoryTest} via the {@code @PreAuthorize}s.
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER','READ_ONLY','HIRING_MANAGER')")
public class PipelineController {

    private final PipelineService service;
    private final PipelineBulkService bulkService;

    public PipelineController(PipelineService service, PipelineBulkService bulkService) {
        this.service = service;
        this.bulkService = bulkService;
    }

    @GetMapping("/api/internal/pipeline")
    public ResponseEntity<PipelinePage> list(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "requisitionId", required = false) String requisitionId,
            @RequestParam(name = "sla", required = false) String sla,
            @RequestParam(name = "scheduling", required = false) String scheduling,
            @RequestParam(name = "stage", required = false) String stage,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "50") int size) {
        PipelineService.Filters filters = new PipelineService.Filters(
            PipelineStatusFilter.parse(status), requisitionId, parseSla(sla), parseScheduling(scheduling), stage);
        if (page < 0 || size <= 0) {
            throw new PipelineExceptions.InvalidRequestException();
        }
        PipelinePage body = service.list(principal.workspaceId(), principal.memberId(), principal.role(),
            filters, PipelineSort.parse(sort), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/api/internal/pipeline/bulk")
    @PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
    public ResponseEntity<BulkResponse> bulk(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody BulkRequest body,
            HttpServletRequest http) {
        BulkResponse res = bulkService.execute(principal.workspaceId(), principal.memberId(), body, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(res);
    }

    @GetMapping("/api/internal/pipeline/candidates/{candidateId}/timeline")
    public ResponseEntity<TimelineResponse> timeline(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String candidateId) {
        TimelineResponse body = service.timeline(principal.workspaceId(), principal.memberId(),
            principal.role(), candidateId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static SlaState parseSla(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return SlaState.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new PipelineExceptions.InvalidRequestException(); }
    }

    private static PipelineSchedulingStatus parseScheduling(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return PipelineSchedulingStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new PipelineExceptions.InvalidRequestException(); }
    }
}
