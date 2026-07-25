package com.cadence.api;

import com.cadence.api.InterviewTemplateDtos.ListResponse;
import com.cadence.api.InterviewTemplateDtos.PresetsResponse;
import com.cadence.api.InterviewTemplateDtos.SlotComputationResponse;
import com.cadence.api.InterviewTemplateDtos.SlotPreviewRequest;
import com.cadence.api.InterviewTemplateDtos.TemplateRequest;
import com.cadence.api.InterviewTemplateDtos.TemplateResponse;
import com.cadence.domain.AuthEventType;
import com.cadence.domain.SlotComputationRequest;
import com.cadence.service.AuthAuditService;
import com.cadence.service.InterviewTemplatePresetCatalogue;
import com.cadence.service.InterviewTemplateService;
import com.cadence.service.RuleEngine;
import com.cadence.service.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interview-template management + the rule-engine slot preview (F12, contract). The class-level
 * {@code @PreAuthorize} is the single source of truth for the role and satisfies the F02
 * RbacEndpointInventoryTest for EVERY handler (including {@code /slots}, which reaches the privileged
 * {@link RuleEngine}/{@code AvailabilityService} — D9). Mounted under the internal (non-allow-listed)
 * prefix so a missing declaration would red the inventory test by design.
 */
@RestController
@RequestMapping("/api/internal/interview-templates")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class InterviewTemplateController {

    private final InterviewTemplateService service;
    private final RuleEngine ruleEngine;
    private final AuthAuditService audit;
    private final InterviewTemplatePresetCatalogue presetCatalogue;

    public InterviewTemplateController(InterviewTemplateService service, RuleEngine ruleEngine, AuthAuditService audit,
            InterviewTemplatePresetCatalogue presetCatalogue) {
        this.service = service;
        this.ruleEngine = ruleEngine;
        this.audit = audit;
        this.presetCatalogue = presetCatalogue;
    }

    @PostMapping
    public ResponseEntity<TemplateResponse> create(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestBody TemplateRequest req) {
        return ResponseEntity.ok(service.create(principal.workspaceId(), principal.memberId(), req));
    }

    @GetMapping
    public ResponseEntity<ListResponse> list(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(value = "status", defaultValue = "ACTIVE") String status) {
        return ResponseEntity.ok(new ListResponse(service.list(principal.workspaceId(), status)));
    }

    /**
     * Code-shipped preset gallery (spec 2026-07-26). Static catalogue, no workspace state, covered by
     * the class-level ADMIN/RECRUITER gate. The literal segment deterministically beats GET /{id}
     * under PathPattern specificity.
     */
    @GetMapping("/presets")
    public ResponseEntity<PresetsResponse> presets() {
        return ResponseEntity.ok(new PresetsResponse(
            presetCatalogue.all().stream().map(InterviewTemplateDtos.PresetDto::from).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemplateResponse> get(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(service.get(principal.workspaceId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TemplateResponse> update(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id,
            @RequestBody TemplateRequest req) {
        return ResponseEntity.ok(service.update(principal.workspaceId(), principal.memberId(), id, req));
    }

    @PostMapping("/{id}/retire")
    public ResponseEntity<TemplateResponse> retire(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id) {
        return ResponseEntity.ok(service.retire(principal.workspaceId(), principal.memberId(), id));
    }

    @PostMapping("/{id}/slots")
    public ResponseEntity<SlotComputationResponse> slots(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String id,
            @RequestBody SlotPreviewRequest req) {
        if (req == null || req.rangeStart() == null || req.rangeEnd() == null) {
            throw new InterviewTemplateExceptions.InvalidTemplateException(
                java.util.Map.of("range", "Both rangeStart and rangeEnd are required."));
        }
        SlotComputationRequest request = new SlotComputationRequest(
            principal.workspaceId(), id, req.rangeStart(), req.rangeEnd());
        try {
            SlotComputationResponse body = SlotComputationResponse.from(ruleEngine.compute(request));
            return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
        } catch (InterviewTemplateExceptions.TemplateRetiredException e) {
            // Audit the refused attempt (ids only, D10) then let the handler render the 409.
            audit.record(AuthEventType.INTERVIEW_TEMPLATE_COMPUTE_REFUSED,
                principal.workspaceId(), principal.memberId(), "retired", null);
            throw e;
        }
    }
}
