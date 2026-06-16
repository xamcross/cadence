package com.cadence.api;

import com.cadence.api.EmailTemplateDtos.ApplyToneRequest;
import com.cadence.api.EmailTemplateDtos.EditRequest;
import com.cadence.api.EmailTemplateDtos.ListResponse;
import com.cadence.api.EmailTemplateDtos.LockRequest;
import com.cadence.api.EmailTemplateDtos.PreviewRequest;
import com.cadence.api.EmailTemplateDtos.RenderedMessageResponse;
import com.cadence.api.EmailTemplateDtos.ResetRequest;
import com.cadence.api.EmailTemplateDtos.TemplateResponse;
import com.cadence.domain.EmailMessageType;
import com.cadence.service.EmailTemplateService;
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
 * Email-template library management + rendering preview (F21, contract). Class-level {@code @PreAuthorize}
 * is the single source of truth for the base role and satisfies the F02 RbacEndpointInventoryTest for
 * EVERY handler; lock/unlock add a method-level ADMIN gate (most-specific wins — D6). A Recruiter editing
 * a LOCKED template is refused by the service (403 {@code template_locked}). Preview is {@code no-store}.
 */
@RestController
@RequestMapping("/api/internal/email-templates")
@PreAuthorize("hasAnyRole('ADMIN','RECRUITER')")
public class EmailTemplateController {

    private final EmailTemplateService service;

    public EmailTemplateController(EmailTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ListResponse> list(
            @AuthenticationPrincipal SessionService.Principal principal,
            @RequestParam(value = "stageKey", defaultValue = "BASE") String stageKey) {
        return ResponseEntity.ok(service.list(principal.workspaceId(), stageKey));
    }

    @GetMapping("/{messageType}")
    public ResponseEntity<TemplateResponse> get(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestParam(value = "stageKey", defaultValue = "BASE") String stageKey) {
        return ResponseEntity.ok(service.get(principal.workspaceId(), parseType(messageType), stageKey));
    }

    @PutMapping("/{messageType}")
    public ResponseEntity<TemplateResponse> edit(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody EditRequest req) {
        return ResponseEntity.ok(service.edit(
            principal.workspaceId(), principal.memberId(), principal.role(), parseType(messageType), req));
    }

    @PostMapping("/{messageType}/apply-tone")
    public ResponseEntity<TemplateResponse> applyTone(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody ApplyToneRequest req) {
        return ResponseEntity.ok(service.applyTone(
            principal.workspaceId(), principal.memberId(), principal.role(), parseType(messageType), req));
    }

    @PostMapping("/{messageType}/reset")
    public ResponseEntity<TemplateResponse> reset(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody ResetRequest req) {
        return ResponseEntity.ok(service.reset(
            principal.workspaceId(), principal.memberId(), principal.role(), parseType(messageType), req));
    }

    @PostMapping("/{messageType}/lock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TemplateResponse> lock(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody LockRequest req) {
        return ResponseEntity.ok(service.setLocked(
            principal.workspaceId(), principal.memberId(), parseType(messageType), req, true));
    }

    @PostMapping("/{messageType}/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TemplateResponse> unlock(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody LockRequest req) {
        return ResponseEntity.ok(service.setLocked(
            principal.workspaceId(), principal.memberId(), parseType(messageType), req, false));
    }

    @PostMapping("/{messageType}/preview")
    public ResponseEntity<RenderedMessageResponse> preview(
            @AuthenticationPrincipal SessionService.Principal principal,
            @PathVariable String messageType,
            @RequestBody PreviewRequest req) {
        RenderedMessageResponse body = RenderedMessageResponse.from(
            service.preview(principal.workspaceId(), parseType(messageType), req));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** An invalid messageType is an indistinguishable scoped not-found (404), never an oracle. */
    private EmailMessageType parseType(String raw) {
        try {
            return EmailMessageType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new RbacExceptions.ScopedNotFoundException();
        }
    }
}
